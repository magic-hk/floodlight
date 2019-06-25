/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.linkdiscovery.internal;

import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.annotation.Nonnull;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.HAListenerTypeMarker;
import net.floodlightcontroller.core.HARole;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IShutdownService;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.IHAListener;
import net.floodlightcontroller.core.IInfoProvider;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.debugcounter.IDebugCounter;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.LDUpdate;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.LinkType;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.SwitchType;
import net.floodlightcontroller.linkdiscovery.ILinkDiscovery.UpdateOperation;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.linkdiscovery.Link;
import net.floodlightcontroller.linkdiscovery.web.LinkDiscoveryWebRoutable;
import net.floodlightcontroller.packet.BSN;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.LLDP;
import net.floodlightcontroller.packet.LLDPTLV;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.storage.IResultSet;
import net.floodlightcontroller.storage.IStorageSourceListener;
import net.floodlightcontroller.storage.IStorageSourceService;
import net.floodlightcontroller.storage.OperatorPredicate;
import net.floodlightcontroller.storage.StorageException;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import net.floodlightcontroller.util.OFMessageUtils;

import org.projectfloodlight.openflow.protocol.OFControllerRole;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFPortState;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class sends out LLDP messages containing the sending switch's datapath
 * id as well as the outgoing port number. Received LLrescDP messages that match
 * a known switch cause a new LinkTuple to be created according to the invariant
 * rules listed below. This new LinkTuple is also passed to routing if it exists
 * to trigger updates. This class also handles removing links that are
 * associated to switch ports that go down, and switches that are disconnected.
 * 该类发送包含发送交换机的datapath id和传出端口号的LLDP消息。
 * 接收到的与已知交换机匹配的LLrescDP消息会导致根据下面列出的不变规则创建新的LinkTuple。
 * 这个新的LinkTuple也被传递给路由，如果它存在以触发更新。该类还处理删除与关闭的交换机端口相关联的链接和断开连接的交换机
 * 
 * Invariants: -portLinks and switchLinks will not contain empty Sets outside of
 * critical sections -portLinks contains LinkTuples where one of the src or dst
 * SwitchPortTuple matches the map key -switchLinks contains LinkTuples where
 * one of the src or dst SwitchPortTuple's id matches the switch id -Each
 * LinkTuple will be indexed into switchLinks for both src.id and dst.id, and
 * portLinks for each src and dst -The updates queue is only added to from
 * within a held write lock
 * 
 * 不变量:-portLinks和switchLinks不包含关键部分之外的空集
 * -portLinks 包含LinkTuples,其中src或者dst 的一个SwitchPortTuple 匹配 map的key值
 * -switchLinks 包含LinkTuples,其中src或者dst 的一个SwitchPortTuple的id 匹配交换机的id.
 * 每个LinkTuple将会以src.id和dst.id为索引，添加到switchLinks.同样以src和dst为索引添加到protLinks.
 * 更新队列仅从持有的写锁中添加
 * 
 * @edited Ryan Izard, rizard@g.clemson.edu, ryan.izard@bigswitch.com
 */
public class LinkDiscoveryManager implements IOFMessageListener,
IOFSwitchListener, IStorageSourceListener, ILinkDiscoveryService,
IFloodlightModule, IInfoProvider {
	protected static final Logger log = LoggerFactory.getLogger(LinkDiscoveryManager.class);
	
	//模块名称
	public static final String MODULE_NAME = "linkdiscovery";

	// Names of table/fields for links in the storage API
	//在存储API中table/fields的名称
	private static final String TOPOLOGY_TABLE_NAME = "controller_topologyconfig";
	private static final String TOPOLOGY_ID = "id";
	private static final String TOPOLOGY_AUTOPORTFAST = "autoportfast";

	private static final String LINK_TABLE_NAME = "controller_link";
	private static final String LINK_ID = "id";
	private static final String LINK_SRC_SWITCH = "src_switch_id";
	private static final String LINK_SRC_PORT = "src_port";
	private static final String LINK_DST_SWITCH = "dst_switch_id";
	private static final String LINK_DST_PORT = "dst_port";
	private static final String LINK_VALID_TIME = "valid_time";
	private static final String LINK_TYPE = "link_type";
	private static final String SWITCH_CONFIG_TABLE_NAME = "controller_switchconfig";

	protected IFloodlightProviderService floodlightProviderService;
	protected IOFSwitchService switchService;
	protected IStorageSourceService storageSourceService;
	protected IThreadPoolService threadPoolService;
	protected IRestApiService restApiService;
	protected IDebugCounterService debugCounterService;
	protected IShutdownService shutdownService;

	// Role
	protected HARole role;

	// LLDP and BDDP fields
	//LLDP则使用组播地址01:80:c2:00:00:0e
	private static final byte[] LLDP_STANDARD_DST_MAC_STRING =
			MacAddress.of("01:80:c2:00:00:0e").getBytes();
	private static final long LINK_LOCAL_MASK = 0xfffffffffff0L;
	private static final long LINK_LOCAL_VALUE = 0x0180c2000000L;
	protected static int EVENT_HISTORY_SIZE = 1024; // in seconds

	// BigSwitch OUI is 5C:16:C7, so 5D:16:C7 is the multicast version
	// private static final String LLDP_BSN_DST_MAC_STRING =
	// "5d:16:c7:00:00:01";
	//BBDP使用广播地址
	private static final String LLDP_BSN_DST_MAC_STRING = "ff:ff:ff:ff:ff:ff";

	// Direction TLVs are used to indicate if the LLDPs were sent
	// periodically or in response to a recieved LLDP
	private static final byte TLV_DIRECTION_TYPE = 0x73;
	private static final short TLV_DIRECTION_LENGTH = 1; // 1 byte
	private static final byte TLV_DIRECTION_VALUE_FORWARD[] = { 0x01 };
	private static final byte TLV_DIRECTION_VALUE_REVERSE[] = { 0x02 };
	private static final LLDPTLV forwardTLV = new LLDPTLV().setType(TLV_DIRECTION_TYPE)
			.setLength(TLV_DIRECTION_LENGTH)
			.setValue(TLV_DIRECTION_VALUE_FORWARD);

	private static final LLDPTLV reverseTLV = new LLDPTLV().setType(TLV_DIRECTION_TYPE)
			.setLength(TLV_DIRECTION_LENGTH)
			.setValue(TLV_DIRECTION_VALUE_REVERSE);

	// Link discovery task details.
	protected SingletonTask discoveryTask;
	protected final int DISCOVERY_TASK_INTERVAL = 1;
	protected final int LINK_TIMEOUT = 35; // timeout as part of LLDP process.
	protected final int LLDP_TO_ALL_INTERVAL = 15; // 15 seconds.
	protected long lldpClock = 0;
	// This value is intentionally kept higher than LLDP_TO_ALL_INTERVAL.
	// If we want to identify link failures faster, we could decrease this
	// value to a small number, say 1 or 2 sec.
	protected final int LLDP_TO_KNOWN_INTERVAL = 20; // LLDP frequency for known
	// links

	protected LLDPTLV controllerTLV;
	protected ReentrantReadWriteLock lock;
	int lldpTimeCount = 0;

	/*
	 * Latency tracking
	 * 时延追踪
	 */
	//时延窗口大小，这里会保存历史时延，link的时延是历史时延的平均值
	protected static int LATENCY_HISTORY_SIZE = 10;
	protected static double LATENCY_UPDATE_THRESHOLD = 0.50;

	/**
	 * Flag to indicate if automatic port fast is enabled or not. Default is set
	 * to false -- Initialized in the init method as well.
	 * 标志，指示是否启用了自动端口快速。默认值设置为false——在init方法中初始化。
	 */
	protected boolean AUTOPORTFAST_DEFAULT = false;
	protected boolean autoPortFastFeature = AUTOPORTFAST_DEFAULT;

	/**
	 * Map from link to the most recent time it was verified functioning
	 */
	protected Map<Link, LinkInfo> links;

	/**
	 * Map from switch id to a set of all links with it as an endpoint
	 * 将指定id的sw作为端点的所有links
	 */
	protected Map<DatapathId, Set<Link>> switchLinks;

	/**
	 * Map from a id:port to the set of links containing it as an endpoint
	 * 将指定id:port作为包含端点的所有links
	 */
	protected Map<NodePortTuple, Set<Link>> portLinks;

	protected volatile boolean shuttingDown = false;

	/*
	 * topology aware components are called in the order they were added to the
	 * the array
	 * 按将拓扑感知组件被添加到数组中的顺序来调用它们
	 */
	protected ArrayList<ILinkDiscoveryListener> linkDiscoveryAware;
	protected BlockingQueue<LDUpdate> updates;
	protected Thread updatesThread;

	/**
	 * List of ports through which LLDP/BDDPs are not sent.
	 * LLDP/BDDPs没有从其发送的一组端口。
	 */
	protected Set<NodePortTuple> suppressLinkDiscovery;

	/**
	 * A list of ports that are quarantined for discovering links through them.
	 * Data traffic from these ports are not allowed until the ports are
	 * released from quarantine.
	 * 一组被隔离的端口，为了发现从它们经过的links.
	 * 数据流量不允许从这些端口出去，直到它们从隔离中释放
	 */
	protected LinkedBlockingQueue<NodePortTuple> quarantineQueue;//隔离队列，已经发送了LLDP,但是还没有收到相关信息的端口，存的是新发现的端口
	protected LinkedBlockingQueue<NodePortTuple> maintenanceQueue;//维护队列，在discoverOnAllPorts，中添加新元素，
	protected LinkedBlockingQueue<NodePortTuple> toRemoveFromQuarantineQueue;
	protected LinkedBlockingQueue<NodePortTuple> toRemoveFromMaintenanceQueue;

	/**
	 * Quarantine task
	 * 隔离任务
	 */
	protected SingletonTask bddpTask;
	protected final int BDDP_TASK_INTERVAL = 100; // 100 ms.
	protected final int BDDP_TASK_SIZE = 10; // # of ports per iteration

	/**
	 * 发送计数，通过LLDP包的接收发情况来计算丢包率 开始
	 * @author hk
	 *
	 */
	protected Map<NodePortTuple,Long> lldpSendCounter;
	protected Map<NodePortTuple,Long> lldpReceiveCounter;
	protected Map<NodePortTuple,Map<Long,Integer>> lldpTimeStampCounter;
	
	protected Map<NodePortTuple,Long> bddpSendCounter;
	protected Map<NodePortTuple,Long> bddpReceiveCounter;
	protected Map<NodePortTuple,Map<Long,Integer>> bddpTimeStampCounter;
	
	@Override
	public Long getLLDPSendCountByNPT(NodePortTuple npt){
		Long count = lldpSendCounter.get(npt);
		return count == null ? 0 : count;
	}
	@Override
	public Long getLLDPReceiveCountByNPT(NodePortTuple npt){
		Long count = lldpReceiveCounter.get(npt);
		return count == null ? 0 : count;
	}
	@Override
	public Long getBDDPSendCountByNPT(NodePortTuple npt){
		Long count = bddpSendCounter.get(npt);
		return count == null ? 0 : count;
	}
	@Override
	public Long getBDDPReceiveCountByNPT(NodePortTuple npt){
		Long count = bddpReceiveCounter.get(npt);
		return count == null ? 0 : count;
	}
	/**
	 * 发送计数，通过LLDP包的接收发情况来计算丢包率 结束
	 * @author hk
	 *
	 */
	
	private class MACRange {
		MacAddress baseMAC;
		int ignoreBits;
	}
	protected Set<MACRange> ignoreMACSet;

	private IHAListener haListener;

	/**
	 * Debug Counters
	 */
	private IDebugCounter ctrQuarantineDrops;
	private IDebugCounter ctrIgnoreSrcMacDrops;
	private IDebugCounter ctrIncoming;
	private IDebugCounter ctrLinkLocalDrops;
	private IDebugCounter ctrLldpEol;
	private IDebugCounter counterPacketOut;

	private final String PACKAGE = LinkDiscoveryManager.class.getPackage().getName();


	//*********************
	// ILinkDiscoveryService
	//*********************

	//返回OFPacketOut,其包含了关联了switchport(sw,port)的LLDP数据
	@Override
	public OFPacketOut generateLLDPMessage(IOFSwitch iofSwitch, OFPort port, 
			boolean isStandard, boolean isReverse) {
		
		long checkTimeStamp =0l;
		
		OFPortDesc ofpPort = iofSwitch.getPort(port);

		if (log.isTraceEnabled()) {
			log.trace("Sending LLDP packet out of swich: {}, port: {}, reverse: {}",
				new Object[] {iofSwitch.getId().toString(), port.toString(), Boolean.toString(isReverse)});
		}

		// using "nearest customer bridge" MAC address for broadest possible
		// propagation
		// through provider and TPMR bridges (see IEEE 802.1AB-2009 and
		// 802.1Q-2011),
		// in particular the Linux bridge which behaves mostly like a provider
		// bridge
		byte[] chassisId = new byte[] { 4, 0, 0, 0, 0, 0, 0 }; // filled in
		// later
		byte[] portId = new byte[] { 2, 0, 0 }; // filled in later
		byte[] ttlValue = new byte[] { 0, 0x78 };
		// OpenFlow OUI - 00-26-E1-00
		byte[] dpidTLVValue = new byte[] { 0x0, 0x26, (byte) 0xe1, 0, 0, 0,
				0, 0, 0, 0, 0, 0 };
		LLDPTLV dpidTLV = new LLDPTLV().setType((byte) 127)
				.setLength((short) dpidTLVValue.length)
				.setValue(dpidTLVValue);

		byte[] dpidArray = new byte[8];
		ByteBuffer dpidBB = ByteBuffer.wrap(dpidArray);
		ByteBuffer portBB = ByteBuffer.wrap(portId, 1, 2);

		DatapathId dpid = iofSwitch.getId();
		dpidBB.putLong(dpid.getLong());
		// set the chassis id's value to last 6 bytes of dpid
		System.arraycopy(dpidArray, 2, chassisId, 1, 6);
		// set the optional tlv to the full dpid
		System.arraycopy(dpidArray, 0, dpidTLVValue, 4, 8);

		// TODO: Consider remove this block of code.
		// It's evil to overwrite port object. The the old code always
		// overwrote mac address, we now only overwrite zero macs and
		// log a warning, mostly for paranoia.
		byte[] srcMac = ofpPort.getHwAddr().getBytes();
		byte[] zeroMac = { 0, 0, 0, 0, 0, 0 };
		if (Arrays.equals(srcMac, zeroMac)) {
			log.warn("Port {}/{} has zero hardware address"
					+ "overwrite with lower 6 bytes of dpid",
					dpid.toString(), ofpPort.getPortNo().getPortNumber());
			System.arraycopy(dpidArray, 2, srcMac, 0, 6);
		}

		// set the portId to the outgoing port
		portBB.putShort(port.getShortPortNumber());

		LLDP lldp = new LLDP();
		lldp.setChassisId(new LLDPTLV().setType((byte) 1)
				.setLength((short) chassisId.length)
				.setValue(chassisId));
		lldp.setPortId(new LLDPTLV().setType((byte) 2)
				.setLength((short) portId.length)
				.setValue(portId));
		lldp.setTtl(new LLDPTLV().setType((byte) 3)
				.setLength((short) ttlValue.length)
				.setValue(ttlValue));
		lldp.getOptionalTLVList().add(dpidTLV);

		// Add the controller identifier to the TLV value.
		lldp.getOptionalTLVList().add(controllerTLV);
		if (isReverse) {
			lldp.getOptionalTLVList().add(reverseTLV);
		} else {
			lldp.getOptionalTLVList().add(forwardTLV);
		}

		/* 
		 * Introduce a new TLV for med-granularity link latency detection.
		 * If same controller, can assume system clock is the same, but
		 * cannot guarantee processing time or account for network congestion.
		 * 
		 * Need to include our OpenFlow OUI - 00-26-E1-01 (note 01; 00 is DPID); 
		 * save last 8 bytes for long (time in ms). 
		 * 
		 * Note Long.SIZE is in bits (64).
		 */
		long time = System.nanoTime() / 1000000;
		long swLatency = iofSwitch.getLatency().getValue();
		if (log.isTraceEnabled()) {
			log.trace("SETTING LLDP LATENCY TLV: Current Time {}; {} control plane latency {}; sum {}", new Object[] { time, iofSwitch.getId(), swLatency, time + swLatency });
		}
		byte[] timestampTLVValue = ByteBuffer.allocate(Long.SIZE / 8 + 4)
				.put((byte) 0x00)
				.put((byte) 0x26)
				.put((byte) 0xe1)
				.put((byte) 0x01) /* 0x01 is what we'll use to differentiate DPID (0x00) from time (0x01) */
				.putLong(time + swLatency /* account for our switch's one-way latency */)
				.array();
		
		checkTimeStamp = time+swLatency;
		
		LLDPTLV timestampTLV = new LLDPTLV()
		.setType((byte) 127)
		.setLength((short) timestampTLVValue.length)
		.setValue(timestampTLVValue);

		/* Now add TLV to our LLDP packet */
		lldp.getOptionalTLVList().add(timestampTLV);

		//封装以太网帧,但是需要判断是发送LDDP还是BDDP
		Ethernet ethernet;
		if (isStandard) {//标准LLDP情况下，发送LDDP包
			ethernet = new Ethernet().setSourceMACAddress(ofpPort.getHwAddr())
					.setDestinationMACAddress(LLDP_STANDARD_DST_MAC_STRING)
					.setEtherType(EthType.LLDP);
			ethernet.setPayload(lldp);
		} else {//非标准情况下，发送BDDP
			BSN bsn = new BSN(BSN.BSN_TYPE_BDDP);
			bsn.setPayload(lldp);

			ethernet = new Ethernet().setSourceMACAddress(ofpPort.getHwAddr())
					.setDestinationMACAddress(LLDP_BSN_DST_MAC_STRING)
					.setEtherType(EthType.of(Ethernet.TYPE_BSN & 0xffff)); /* treat as unsigned */
			ethernet.setPayload(bsn);
		}

		// serialize and wrap in a packet out
		//序列化并封装成包
		byte[] data = ethernet.serialize();
		OFPacketOut.Builder pob = iofSwitch.getOFFactory().buildPacketOut()
		.setBufferId(OFBufferId.NO_BUFFER)
		.setActions(getDiscoveryActions(iofSwitch, port))
		.setData(data);
		OFMessageUtils.setInPort(pob, OFPort.CONTROLLER);
		
		//添加时间戳,这里把lldp和bddp分开的原因在于可能会受到得不到lldp包的影响
//		if(isStandard) {
			//获取对应NodePortTuple的交换机的，timeStampMap
			NodePortTuple npt = new NodePortTuple(iofSwitch.getId(),port);
			//获取不同包类型对应的一个时间戳计数器
			Map<NodePortTuple, Map<Long, Integer>> timeStampCounter=lldpTimeStampCounter;
			if(!isStandard) {//如果不是标准的用bddp计数器
				timeStampCounter = bddpTimeStampCounter;
			}
			//获取时间戳Map映射
			Map<Long,Integer> timeStampMap = timeStampCounter.get(npt);
			if(timeStampMap==null) {//如果不存在则添加新映射
				timeStampMap = new HashMap<Long,Integer>();
			}
			timeStampMap.put(new Long(checkTimeStamp), 1);
			timeStampCounter.put(npt, timeStampMap);
			
			
//		}

		log.debug("{}", pob.build());
		return pob.build();
	}

	/**
	 * Get the LLDP sending period in seconds.
	 *
	 * @return LLDP sending period in seconds.
	 */
	public int getLldpFrequency() {
		return LLDP_TO_KNOWN_INTERVAL;
	}

	/**
	 * Get the LLDP timeout value in seconds
	 *
	 * @return LLDP timeout value in seconds
	 */
	public int getLldpTimeout() {
		return LINK_TIMEOUT;
	}

	@Override
	public Map<NodePortTuple, Set<Link>> getPortLinks() {
		return portLinks;
	}

	@Override
	public Set<NodePortTuple> getSuppressLLDPsInfo() {
		return suppressLinkDiscovery;
	}

	/**
	 * Add a switch port to the suppressed LLDP list. Remove any known links on
	 * the switch port.
	 */
	@Override
	public void AddToSuppressLLDPs(DatapathId sw, OFPort port) {
		NodePortTuple npt = new NodePortTuple(sw, port);
		this.suppressLinkDiscovery.add(npt);
		deleteLinksOnPort(npt, "LLDP suppressed.");
	}

	/**
	 * Remove a switch port from the suppressed LLDP list. Discover links on
	 * that switchport.
	 */
	@Override
	public void RemoveFromSuppressLLDPs(DatapathId sw, OFPort port) {
		NodePortTuple npt = new NodePortTuple(sw, port);
		this.suppressLinkDiscovery.remove(npt);
		discover(npt);
	}

	public boolean isShuttingDown() {
		return shuttingDown;
	}

	@Override
	public boolean isTunnelPort(DatapathId sw, OFPort port) {
		return false;
	}

	@Override
	public ILinkDiscovery.LinkType getLinkType(Link lt, LinkInfo info) {
		if (info.getUnicastValidTime() != null) {//lastLldpReceivedTime!=null,即收到过LLDP包
			return ILinkDiscovery.LinkType.DIRECT_LINK;
		} else if (info.getMulticastValidTime() != null) {//lastBddpReceivedTime!=null,这里是收到过LLDp，但是没有收到过BDDP
			return ILinkDiscovery.LinkType.MULTIHOP_LINK;
		}
		return ILinkDiscovery.LinkType.INVALID_LINK;//其它情况，认为是无效link
	}

	@Override
	public Set<OFPort> getQuarantinedPorts(DatapathId sw) {
		Set<OFPort> qPorts = new HashSet<OFPort>();

		Iterator<NodePortTuple> iter = quarantineQueue.iterator();
		while (iter.hasNext()) {
			NodePortTuple npt = iter.next();
			if (npt.getNodeId().equals(sw)) {
				qPorts.add(npt.getPortId());
			}
		}
		return qPorts;
	}

	@Override
	public Map<DatapathId, Set<Link>> getSwitchLinks() {
		return this.switchLinks;
	}

	@Override
	public void addMACToIgnoreList(MacAddress mac, int ignoreBits) {
		MACRange range = new MACRange();
		range.baseMAC = mac;
		range.ignoreBits = ignoreBits;
		ignoreMACSet.add(range);
	}

	@Override
	public boolean isAutoPortFastFeature() {
		return autoPortFastFeature;
	}

	@Override
	public void setAutoPortFastFeature(boolean autoPortFastFeature) {
		this.autoPortFastFeature = autoPortFastFeature;
	}

	@Override
	public void addListener(ILinkDiscoveryListener listener) {
		linkDiscoveryAware.add(listener);
	}

	@Override
	public Map<Link, LinkInfo> getLinks() {
		lock.readLock().lock();
		Map<Link, LinkInfo> result;
		try {
			result = new HashMap<Link, LinkInfo>(links);
		} finally {
			lock.readLock().unlock();
		}
		return result;
	}

	@Override
	public LinkInfo getLinkInfo(Link link) {
		lock.readLock().lock();
		LinkInfo linkInfo = links.get(link);
		LinkInfo retLinkInfo = null;
		if (linkInfo != null) {
			retLinkInfo = new LinkInfo(linkInfo);
		}
		lock.readLock().unlock();
		return retLinkInfo;
	}

	@Override
	public String getName() {
		return MODULE_NAME;
	}

	//*********************
	//   OFMessage Listener
	//*********************

	@Override
	public Command receive(IOFSwitch sw, OFMessage msg,
			FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			ctrIncoming.increment();
			return this.handlePacketIn(sw.getId(), (OFPacketIn) msg,
					cntx);
		default:
			break;
		}
		return Command.CONTINUE;
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	//***********************************
	//  Internal Methods - Packet-in Processing Related
	//内部方法：包进入处理相关
	//***********************************

	protected Command handlePacketIn(DatapathId sw, OFPacketIn pi,
			FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx,
				IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		if (eth.getPayload() instanceof BSN) {
			BSN bsn = (BSN) eth.getPayload();
			if (bsn == null) return Command.STOP;
			if (bsn.getPayload() == null) return Command.STOP;
			// It could be a packet other than BSN LLDP, therefore
			// continue with the regular processing.
			if (bsn.getPayload() instanceof LLDP == false)
				return Command.CONTINUE;
			return handleLldp((LLDP) bsn.getPayload(), sw, inPort, false, cntx);
		} else if (eth.getPayload() instanceof LLDP) {
			return handleLldp((LLDP) eth.getPayload(), sw, inPort, true, cntx);
		} else if (eth.getEtherType().getValue() < 1536 && eth.getEtherType().getValue() >= 17) {
			long destMac = eth.getDestinationMACAddress().getLong();
			if ((destMac & LINK_LOCAL_MASK) == LINK_LOCAL_VALUE) {
				ctrLinkLocalDrops.increment();
				if (log.isTraceEnabled()) {
					log.trace("Ignoring packet addressed to 802.1D/Q "
							+ "reserved address.");
				}
				return Command.STOP;
			}
		} else if (eth.getEtherType().getValue() < 17) {
			log.error("Received invalid ethertype of {}.", eth.getEtherType());
			return Command.STOP;
		}

		if (ignorePacketInFromSource(eth.getSourceMACAddress())) {
			ctrIgnoreSrcMacDrops.increment();
			return Command.STOP;
		}

		// If packet-in is from a quarantine port, stop processing.
		NodePortTuple npt = new NodePortTuple(sw, inPort);
		if (quarantineQueue.contains(npt)) {
			ctrQuarantineDrops.increment();
			return Command.STOP;
		}

		return Command.CONTINUE;
	}

	private boolean ignorePacketInFromSource(MacAddress srcMAC) {
		Iterator<MACRange> it = ignoreMACSet.iterator();
		while (it.hasNext()) {
			MACRange range = it.next();
			long mask = ~0;
			if (range.ignoreBits >= 0 && range.ignoreBits <= 48) {
				mask = mask << range.ignoreBits;
				if ((range.baseMAC.getLong() & mask) == (srcMAC.getLong() & mask)) {
					return true;
				}
			}
		}
		return false;
	}

	private Command handleLldp(LLDP lldp, DatapathId sw, OFPort inPort,
			boolean isStandard, FloodlightContext cntx) {
		long checktimestamp=0l;
		
		
		// If LLDP is suppressed on this port, ignore received packet as well
		IOFSwitch iofSwitch = switchService.getSwitch(sw);

		log.debug("Received LLDP packet on sw {}, port {}", sw, inPort);

		//判断这个端口是否被抑制
		if (!isIncomingDiscoveryAllowed(sw, inPort, isStandard))
			return Command.STOP;

		// If this is a malformed LLDP exit
		if (lldp.getPortId() == null || lldp.getPortId().getLength() != 3) {
			return Command.STOP;
		}

		//获取当前控制器的id
		long myId = ByteBuffer.wrap(controllerTLV.getValue()).getLong();
		long otherId = 0;
		boolean myLLDP = false;
		Boolean isReverse = null;

		ByteBuffer portBB = ByteBuffer.wrap(lldp.getPortId().getValue());
		portBB.position(1);

		OFPort remotePort = OFPort.of(portBB.getShort());
		IOFSwitch remoteSwitch = null;
		long timestamp = 0;

		// Verify this LLDP packet matches what we're looking for
		//遍历LLDP的可变部分
		for (LLDPTLV lldptlv : lldp.getOptionalTLVList()) {
			if (lldptlv.getType() == 127 && lldptlv.getLength() == 12
					&& lldptlv.getValue()[0] == 0x0
					&& lldptlv.getValue()[1] == 0x26
					&& lldptlv.getValue()[2] == (byte) 0xe1
					&& lldptlv.getValue()[3] == 0x0) {
				ByteBuffer dpidBB = ByteBuffer.wrap(lldptlv.getValue());
				//读取交换机ID
				remoteSwitch = switchService.getSwitch(DatapathId.of(dpidBB.getLong(4)));
			} else if (lldptlv.getType() == 127 && lldptlv.getLength() == 12
					&& lldptlv.getValue()[0] == 0x0
					&& lldptlv.getValue()[1] == 0x26
					&& lldptlv.getValue()[2] == (byte) 0xe1
					&& lldptlv.getValue()[3] == 0x01) { /* 0x01 for timestamp */
				ByteBuffer tsBB = ByteBuffer.wrap(lldptlv.getValue()); /* skip OpenFlow OUI (4 bytes above) */
				//收到LLDP包的交换机到controller的延迟。
				long swLatency = iofSwitch.getLatency().getValue();
				//这里的timestamp是控制器产生LLDP的时间戳+从控制器到交换机的时延迟，可以认为是SW发出LLDP的时间戳
				timestamp = tsBB.getLong(4); /* include the RX switch latency to "subtract" it */
				
				//根据时间戳来得知对应的发送LLDP
				checktimestamp = timestamp;
				
				if (log.isTraceEnabled()) {
					log.trace("RECEIVED LLDP LATENCY TLV: Got timestamp of {}; Switch {} latency of {}", new Object[] { timestamp, iofSwitch.getId(), iofSwitch.getLatency().getValue() }); 
				}
				timestamp = timestamp + swLatency;
			} else if (lldptlv.getType() == 12 && lldptlv.getLength() == 8) {
				otherId = ByteBuffer.wrap(lldptlv.getValue()).getLong();
				if (myId == otherId) myLLDP = true;
			} else if (lldptlv.getType() == TLV_DIRECTION_TYPE
					&& lldptlv.getLength() == TLV_DIRECTION_LENGTH) {
				if (lldptlv.getValue()[0] == TLV_DIRECTION_VALUE_FORWARD[0])
					isReverse = false;
				else if (lldptlv.getValue()[0] == TLV_DIRECTION_VALUE_REVERSE[0])
					isReverse = true;
			}
		}
		//如果收到的lldp不是由本controller发出的话 
		if (myLLDP == false) {
			// This is not the LLDP sent by this controller.
			// If the LLDP message has multicast bit set, then we need to
			// broadcast the packet as a regular packet (after checking IDs)
			//如果收到的是标准LLDP则不处理
			if (isStandard) {
				if (log.isTraceEnabled()) {
					log.trace("Got a standard LLDP=[{}] that was not sent by" +
							" this controller. Not fowarding it.", lldp.toString());
				}
				return Command.STOP;
			} else if (myId < otherId) {//如果当前收到的是BDDP,同时当前controller ID小于发出BDDP的ID，则继续处理
				if (log.isTraceEnabled()) {
					log.trace("Getting BDDP packets from a different controller"
							+ "and letting it go through normal processing chain.");
				}
				return Command.CONTINUE;
			}
			return Command.STOP;
		}

		if (remoteSwitch == null) {
			// Ignore LLDPs not generated by Floodlight, or from a switch that
			// has recently
			// disconnected, or from a switch connected to another Floodlight
			// instance
			if (log.isTraceEnabled()) {
				log.trace("Received LLDP from remote switch not connected to the controller");
			}
			return Command.STOP;
		}

		if (!remoteSwitch.portEnabled(remotePort)) {
			if (log.isTraceEnabled()) {
				log.trace("Ignoring link with disabled source port: switch {} port {} {}",
						new Object[] { remoteSwitch.getId().toString(),
						remotePort,
						remoteSwitch.getPort(remotePort)});
			}
			return Command.STOP;
		}
		if (suppressLinkDiscovery.contains(new NodePortTuple(
				remoteSwitch.getId(),
				remotePort))) {
			if (log.isTraceEnabled()) {
				log.trace("Ignoring link with suppressed src port: switch {} port {} {}",
						new Object[] { remoteSwitch.getId().toString(),
						remotePort,
						remoteSwitch.getPort(remotePort)});
			}
			return Command.STOP;
		}
		if (!iofSwitch.portEnabled(inPort)) {
			if (log.isTraceEnabled()) {
				log.trace("Ignoring link with disabled dest port: switch {} port {} {}",
						new Object[] { sw.toString(),
						inPort.getPortNumber(),
						iofSwitch.getPort(inPort).getPortNo().getPortNumber()});
			}
			return Command.STOP;
		}

		// Store the time of update to this link, and push it out to
		// routingEngine
		long time = System.nanoTime() / 1000000;
		U64 latency = (timestamp != 0 && (time - timestamp) > 0) ? U64.of(time - timestamp) : U64.ZERO;
		if (log.isTraceEnabled()) {
			log.trace("COMPUTING FINAL DATAPLANE LATENCY: Current time {}; Dataplane+{} latency {}; Overall latency from {} to {} is {}", 
					new Object[] { time, iofSwitch.getId(), timestamp, remoteSwitch.getId(), iofSwitch.getId(), String.valueOf(latency.getValue()) });
		}
		Link lt = new Link(remoteSwitch.getId(), remotePort,
				iofSwitch.getId(), inPort, latency);

		if (!isLinkAllowed(lt.getSrc(), lt.getSrcPort(),
				lt.getDst(), lt.getDstPort()))
			return Command.STOP;

		// Continue only if link is allowed.
		Date lastLldpTime = null;
		Date lastBddpTime = null;

		Date firstSeenTime = new Date(System.currentTimeMillis());

		if (isStandard) {
			lastLldpTime = new Date(firstSeenTime.getTime());
		} else {
			lastBddpTime = new Date(firstSeenTime.getTime());
		}

		LinkInfo newLinkInfo = new LinkInfo(firstSeenTime, lastLldpTime, lastBddpTime);
		
		//保存链路信息,这里会判断链路是否发生了转换，以及是否需要更新时延
		addOrUpdateLink(lt, newLinkInfo);

		// Check if reverse link exists.
		// If it doesn't exist and if the forward link was seen
		// first seen within a small interval, send probe on the
		// reverse link.
		//检查反向link是否存在，如果它不存在并且如果转发link在一个小间隔中第一次被看到，则在反向路径上发送探测。
		newLinkInfo = links.get(lt);
		if (newLinkInfo != null && isStandard && isReverse == false) {
			Link reverseLink = new Link(lt.getDst(), lt.getDstPort(),
					lt.getSrc(), lt.getSrcPort(), U64.ZERO); /* latency not used; not important what the value is, since it's intentionally not in equals() */
			LinkInfo reverseInfo = links.get(reverseLink);
			if (reverseInfo == null) {
				// the reverse link does not exist.
				if (newLinkInfo.getFirstSeenTime().getTime() > System.currentTimeMillis()
						- LINK_TIMEOUT) {
					log.debug("Sending reverse LLDP for link {}", lt);
					this.sendDiscoveryMessage(lt.getDst(), lt.getDstPort(),
							isStandard, true);
				}
			}
		}

		// If the received packet is a BDDP packet, then create a reverse BDDP
		// link as well.
		//如果是收到一个BDDP包，再创建一个反向的BDDP link(前面已经创建了一个正向的了)
		if (!isStandard) {
			Link reverseLink = new Link(lt.getDst(), lt.getDstPort(),
					lt.getSrc(), lt.getSrcPort(), latency);

			// srcPortState and dstPort state are reversed.
			LinkInfo reverseInfo = new LinkInfo(firstSeenTime, lastLldpTime,
					lastBddpTime);//这里根据lastBddpTime和lastLldpTime是否为空判断是否是Bddp得到的Link，LinkType.MULTIHOP_LINK

			addOrUpdateLink(reverseLink, reverseInfo);
		}

		// Queue removal of the node ports from the quarantine and maintenance queues.
		NodePortTuple nptSrc = new NodePortTuple(lt.getSrc(),
				lt.getSrcPort());
		NodePortTuple nptDst = new NodePortTuple(lt.getDst(),
				lt.getDstPort());

		flagToRemoveFromQuarantineQueue(nptSrc);
		flagToRemoveFromMaintenanceQueue(nptSrc);
		flagToRemoveFromQuarantineQueue(nptDst);
		flagToRemoveFromMaintenanceQueue(nptDst);

		// Consume this message
		ctrLldpEol.increment();
		
		/**
		 * 接收加1
		 */
		if(remoteSwitch!=null) {//isStandard&&remoteSwitch!=null
			NodePortTuple remote = new NodePortTuple(remoteSwitch.getId(),remotePort);
			//获取不同包类型对应的一个时间戳计数器
			Map<NodePortTuple, Map<Long, Integer>> timeStampCounter = lldpTimeStampCounter;
			Map<NodePortTuple, Long> sendCounter = lldpSendCounter;
			Map<NodePortTuple, Long> receiveCounter = lldpReceiveCounter;		
			if(!isStandard) {//如果不是标准的用bddp计数器
				timeStampCounter = bddpTimeStampCounter;
				sendCounter = bddpSendCounter;
				receiveCounter = bddpReceiveCounter;
			}
			//远端的时间戳存在，说明是当前控制器发送出的包
			if(timeStampCounter.get(remote)!=null&&timeStampCounter.get(remote).get(new Long(checktimestamp))!=null) {
				//移除对应的时间戳
				timeStampCounter.get(remote).remove(new Long(checktimestamp));
				if(receiveCounter.get(remote)==null) {//接收计数器初始化
					receiveCounter.put(remote, 0l);
				}
				receiveCounter.put(remote, receiveCounter.get(remote)+1);
				//远端的发送包计数器不为空，且减去接收包计数器值大于1，输出丢包率
				if(sendCounter.get(remote)!=null&&(sendCounter.get(remote)-receiveCounter.get(remote))>=1) {
//					log.info("---PK LOSS---");
//					log.info("-----------------NPT:{} PK LOSS:{}%",remote,(sendCounter.get(remote)-receiveCounter.get(remote))*100.00/sendCounter.get(remote));
//					log.info("---PK LOSS---");
				}
				//log.info("---PK LOSS Received---");	
			}
		}
		return Command.STOP;
	}

	//***********************************
	//  Internal Methods - Port Status/ New Port Processing Related
	//***********************************
	/**
	 * Process a new port. If link discovery is disabled on the port, then do
	 * nothing. If autoportfast feature is enabled and the port is a fast port,
	 * then do nothing. Otherwise, send LLDP message. Add the port to
	 * quarantine.
	 * 处理一个新端口。如果端口上的链接发现被禁用，那么什么也不做。
	 * 如果启用了autoportfast特性，并且端口是一个快速端口，那么什么也不做。
	 * 否则，发送LLDP消息。将端口加入隔离。
	 * 
	 * @param sw
	 * @param p
	 */
	private void processNewPort(DatapathId sw, OFPort p) {
		if (isLinkDiscoverySuppressed(sw, p)) {
			// Do nothing as link discovery is suppressed.
			return;
		}

		IOFSwitch iofSwitch = switchService.getSwitch(sw);
		if (iofSwitch == null) {
			return;
		}

		NodePortTuple npt = new NodePortTuple(sw, p);
		discover(sw, p);
		addToQuarantineQueue(npt);
	}

	//***********************************
	//  Internal Methods - Discovery Related
	//***********************************

	private void doUpdatesThread() throws InterruptedException {
		do {
			LDUpdate update = updates.take();
			List<LDUpdate> updateList = new ArrayList<LDUpdate>();
			updateList.add(update);

			// Add all the pending updates to the list.
			while (updates.peek() != null) {
				updateList.add(updates.remove());
			}

			if (linkDiscoveryAware != null && !updateList.isEmpty()) {
				if (log.isDebugEnabled()) {
					log.debug("Dispatching link discovery update {} {} {} {} {} {}ms for {}",
							new Object[] {
							update.getOperation(),
							update.getSrc(),
							update.getSrcPort(),
							update.getDst(),
							update.getDstPort(),
							update.getLatency().getValue(),
							linkDiscoveryAware });
				}
				try {
					for (ILinkDiscoveryListener lda : linkDiscoveryAware) { // order
						// maintained
						lda.linkDiscoveryUpdate(updateList);
					}
				} catch (Exception e) {
					log.error("Error in link discovery updates loop", e);
				}
			}
		} while (updates.peek() != null);
	}

	protected boolean isLinkDiscoverySuppressed(DatapathId sw, OFPort portNumber) {
		return this.suppressLinkDiscovery.contains(new NodePortTuple(sw,
				portNumber));
	}

	//线程中调用
	protected void discoverLinks() {

		// timeout known links.
		timeoutLinks();

		// increment LLDP clock
		lldpClock = (lldpClock + 1) % LLDP_TO_ALL_INTERVAL;

		if (lldpClock == 0) {
			if (log.isTraceEnabled())
				log.trace("Sending LLDP out on all ports.");
			discoverOnAllPorts();
		}
	}

	/**
	 * Quarantine Ports.
	 * 隔离端口
	 */
	protected class QuarantineWorker implements Runnable {
		@Override
		public void run() {
			try {
				processBDDPLists();
			} catch (Exception e) {
				log.error("Error in quarantine worker thread", e);
			} finally {
				bddpTask.reschedule(BDDP_TASK_INTERVAL,
						TimeUnit.MILLISECONDS);
			}
		}
	}

	/**
	 * Add a switch port to the quarantine queue. Schedule the quarantine task
	 * if the quarantine queue was empty before adding this switch port.
	 *
	 * @param npt
	 */
	protected void addToQuarantineQueue(NodePortTuple npt) {
		if (quarantineQueue.contains(npt) == false) {
			quarantineQueue.add(npt);
		}
	}

	/**
	 * Remove a switch port from the quarantine queue.
	 *
	protected void removeFromQuarantineQueue(NodePortTuple npt) {
		// Remove all occurrences of the node port tuple from the list.
		while (quarantineQueue.remove(npt));
	}*/
	protected void flagToRemoveFromQuarantineQueue(NodePortTuple npt) {
		if (toRemoveFromQuarantineQueue.contains(npt) == false) {
			toRemoveFromQuarantineQueue.add(npt);
		}
	}

	/**
	 * Add a switch port to maintenance queue.
	 *
	 * @param npt
	 */
	protected void addToMaintenanceQueue(NodePortTuple npt) {
		if (maintenanceQueue.contains(npt) == false) {
			maintenanceQueue.add(npt);
		}
	}

	/**
	 * Remove a switch port from maintenance queue.
	 *
	 * @param npt
	 *
	protected void removeFromMaintenanceQueue(NodePortTuple npt) {
		// Remove all occurrences of the node port tuple from the queue.
		while (maintenanceQueue.remove(npt));
	} */
	protected void flagToRemoveFromMaintenanceQueue(NodePortTuple npt) {
		if (toRemoveFromMaintenanceQueue.contains(npt) == false) {
			toRemoveFromMaintenanceQueue.add(npt);
		}
	}

	/**
	 * This method processes the quarantine list in bursts. The task is at most
	 * once per BDDP_TASK_INTERVAL. One each call, BDDP_TASK_SIZE number of
	 * switch ports are processed. Once the BDDP packets are sent out through
	 * the switch ports, the ports are removed from the quarantine list.
	 * 该方法以突发方式处理隔离list。每个BDDP_TASK_INTERVAL任务最多运行一次。
	 * 每次调用一个BDDP_TASK_SIZE交换机端口的数量被处理。一旦BDDP包通过交换机端口发送出去，这些端口将从隔离列表中删除。
	 */
	protected void processBDDPLists() {
		int count = 0;
		Set<NodePortTuple> nptList = new HashSet<NodePortTuple>();

		//从隔离队列移除
		while (count < BDDP_TASK_SIZE && quarantineQueue.peek() != null) {
			NodePortTuple npt;
			npt = quarantineQueue.remove();
			/*
			 * Do not send a discovery message if we already have received one
			 * from another switch on this same port. In other words, if
			 * handleLldp() determines there is a new link between two ports of
			 * two switches, then there is no need to re-discover the link again.
			 * 如果我们已经从相同端口上的收到另一个sw发送的消息，那么不要发送发现消息。
			 * 换句话说，如果handleLldp()确定在两个交换机的两个端口之间有一个新链接，那么就不需要重新发现该链接
			 * 
			 * 
			 * By flagging the item in handleLldp() and waiting to remove it 
			 * from the queue when processBDDPLists() runs, we can guarantee a 
			 * PORT_STATUS update is generated and dispatched below by
			 * generateSwitchPortStatusUpdate().
			 * 通过在handleLldp()中标记该项，并在processBDDPLists()运行时等待将其从队列中删除，
			 * 我们可以保证由generateSwitchPortStatusUpdate()生成并发出PORT_STATUS更新。
			 * 
			 * 这种情况就是这2个交换机间发现新的直连的链接，这个时候只是标记了端口，还没有将其从隔离端口中移除。
			 */
			if (!toRemoveFromQuarantineQueue.remove(npt)) {//如果npt不在隔离要移除的队列中，那么发送BBDP
				sendDiscoveryMessage(npt.getNodeId(), npt.getPortId(), false, false);
			}
			/*
			 * Still add the item to the list though, so that the PORT_STATUS update
			 * is generated below at the end of this function.
			 * 不过，仍然要将该项添加到列表中，以便在此函数末尾处生成PORT_STATUS更新。
			 */
			nptList.add(npt);
			count++;
		}

		//从维持队列移除
		count = 0;
		while (count < BDDP_TASK_SIZE && maintenanceQueue.peek() != null) {
			NodePortTuple npt;
			npt = maintenanceQueue.remove();
			/*
			 * Same as above, except we don't care about the PORT_STATUS message; 
			 * we only want to avoid sending the discovery message again.
			 * 和上面一样，只是我们不关心PORT_STATUS消息;我们只想避免再次发送发现消息。
			 */
			if (!toRemoveFromMaintenanceQueue.remove(npt)) {//如果npt不在维持队列中要移除的队列中，发送BDDP
				sendDiscoveryMessage(npt.getNodeId(), npt.getPortId(), false, false);
			}
			count++;
		}

		//生成并发出PORT_STATUS更新
		for (NodePortTuple npt : nptList) {
			generateSwitchPortStatusUpdate(npt.getNodeId(), npt.getPortId());
		}
	}

	private void generateSwitchPortStatusUpdate(DatapathId sw, OFPort port) {
		UpdateOperation operation;

		IOFSwitch iofSwitch = switchService.getSwitch(sw);
		if (iofSwitch == null) return;

		OFPortDesc ofp = iofSwitch.getPort(port);
		if (ofp == null) return;

		Set<OFPortState> srcPortState = ofp.getState();
		boolean portUp = !srcPortState.contains(OFPortState.STP_BLOCK);

		if (portUp) {
			operation = UpdateOperation.PORT_UP;
		} else {
			operation = UpdateOperation.PORT_DOWN;
		}

		updates.add(new LDUpdate(sw, port, operation));
	}

	protected void discover(NodePortTuple npt) {
		discover(npt.getNodeId(), npt.getPortId());
	}

	protected void discover(DatapathId sw, OFPort port) {
		sendDiscoveryMessage(sw, port, true, false);
	}

	/**
	 * Check if incoming discovery messages are enabled or not.
	 * @param sw
	 * @param port
	 * @param isStandard
	 * @return
	 */
	protected boolean isIncomingDiscoveryAllowed(DatapathId sw, OFPort port,
			boolean isStandard) {

		if (isLinkDiscoverySuppressed(sw, port)) {
			/* Do not process LLDPs from this port as suppressLLDP is set */
			return false;
		}

		IOFSwitch iofSwitch = switchService.getSwitch(sw);
		if (iofSwitch == null) {
			return false;
		}

		if (port == OFPort.LOCAL) return false;

		OFPortDesc ofpPort = iofSwitch.getPort(port);
		if (ofpPort == null) {
			if (log.isTraceEnabled()) {
				log.trace("Null physical port. sw={}, port={}",
						sw.toString(), port.getPortNumber());
			}
			return false;
		}

		return true;
	}

	/**
	 * Check if outgoing discovery messages are enabled or not.
	 * @param sw
	 * @param port
	 * @param isStandard
	 * @param isReverse
	 * @return
	 */
	protected boolean isOutgoingDiscoveryAllowed(DatapathId sw, OFPort port,
			boolean isStandard,
			boolean isReverse) {

		if (isLinkDiscoverySuppressed(sw, port)) {
			/* Dont send LLDPs out of this port as suppressLLDP is set */
			return false;
		}

		IOFSwitch iofSwitch = switchService.getSwitch(sw);
		if (iofSwitch == null) {
			return false;
		} else if (iofSwitch.getControllerRole() == OFControllerRole.ROLE_SLAVE) {
			return false;
		}

		if (port == OFPort.LOCAL) return false;

		OFPortDesc ofpPort = iofSwitch.getPort(port);
		if (ofpPort == null) {
			if (log.isTraceEnabled()) {
				log.trace("Null physical port. sw={}, port={}",
						sw.toString(), port.getPortNumber());
			}
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Get the actions for packet-out corresponding to a specific port.
	 * This is a placeholder for adding actions if any port-specific
	 * actions are desired.  The default action is simply to output to
	 * the given port.
	 * @param port
	 * @return
	 */
	protected List<OFAction> getDiscoveryActions(IOFSwitch sw, OFPort port) {
		// set actions
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().buildOutput().setPort(port).build());
		return actions;
	}

	/**
	 * Send link discovery message out of a given switch port. The discovery
	 * message may be a standard LLDP or a modified LLDP, where the dst mac
	 * address is set to :ff. TODO: The modified LLDP will updated in the future
	 * and may use a different eth-type.
	 * 从给定的sw 端口发送link 发现消息。这个发现消息可能是一个标准的LLDP也可能是一个修改的LLDP，其dst mac地址被设置为：ff
	 * @param sw
	 * @param port
	 * @param isStandard
	 *            indicates standard or modified LLDP
	 *            表明是标准的或者是修改的LLDP
	 * @param isReverse
	 *            indicates whether the LLDP was sent as a response
	 *            表明LLDP是否作为一个response发送出去
	 */
	protected boolean sendDiscoveryMessage(DatapathId sw, OFPort port,
			boolean isStandard, boolean isReverse) {

		// Takes care of all checks including null pointer checks.
		if (!isOutgoingDiscoveryAllowed(sw, port, isStandard, isReverse)) {
			return false;
		}

		IOFSwitch iofSwitch = switchService.getSwitch(sw);
		if (iofSwitch == null) { // fix dereference violations in case race conditions
			return false;
		}
		counterPacketOut.increment();
		//生成对应sw和端口的LLDP,并将其写到输出流
		boolean result = iofSwitch.write(generateLLDPMessage(iofSwitch, port, isStandard, isReverse));
		
		if(result) {
			/**
			 * 发送计数
			 */
			//if(isStandard) {//如果是LLDP
				NodePortTuple now = new NodePortTuple(iofSwitch.getId(),port);
				Map<NodePortTuple, Long> sendCounter = lldpSendCounter;
				if(!isStandard) {
					sendCounter = bddpSendCounter;
				}
				if(sendCounter.get(now)==null) {
					sendCounter.put(now, 0l);
				}
				sendCounter.put(now , sendCounter.get(now)+1);
			
			//}
		}
		
		return result;
	}

	/**
	 * Send LLDPs to all switch-ports
	 * 向所有的switch-ports来发送LLDPS
	 */
	protected void discoverOnAllPorts() {
		log.info("Sending LLDP packets out of all the enabled ports");
		// Send standard LLDPs
		//获取所有的交换机id
		for (DatapathId sw : switchService.getAllSwitchDpids()) {
			IOFSwitch iofSwitch = switchService.getSwitch(sw);
			if (iofSwitch == null) continue;
			if (!iofSwitch.isActive()) continue; /* can't do anything if the switch is SLAVE */
			Collection<OFPort> c = iofSwitch.getEnabledPortNumbers();
			if (c != null) {
				for (OFPort ofp : c) {
					if (isLinkDiscoverySuppressed(sw, ofp)) {			
						continue;
					}
					log.trace("Enabled port: {}", ofp);
					//向所有交换机的所有可用端口发送LLDP
					sendDiscoveryMessage(sw, ofp, true, false);

					// If the switch port is not already in the maintenance
					// queue, add it. 如果其不在维护队列中，添加它
					NodePortTuple npt = new NodePortTuple(sw, ofp);
					addToMaintenanceQueue(npt);
				}
			}
		}
	}

	protected UpdateOperation getUpdateOperation(OFPortState srcPortState, OFPortState dstPortState) {
		boolean added = ((srcPortState != OFPortState.STP_BLOCK) && (dstPortState != OFPortState.STP_BLOCK));

		if (added) {
			return UpdateOperation.LINK_UPDATED;
		} else {
			return UpdateOperation.LINK_REMOVED;
		}
	}

	protected UpdateOperation getUpdateOperation(OFPortState srcPortState) {
		boolean portUp = (srcPortState != OFPortState.STP_BLOCK);

		if (portUp) {
			return UpdateOperation.PORT_UP;
		} else {
			return UpdateOperation.PORT_DOWN;
		}
	}

	//************************************
	// Internal Methods - Link Operations Related
	//************************************

	/**
	 * This method is used to specifically ignore/consider specific links.
	 */
	protected boolean isLinkAllowed(DatapathId src, OFPort srcPort,
			DatapathId dst, OFPort dstPort) {
		return true;
	}

	private boolean addLink(Link lt, LinkInfo newInfo) {
		NodePortTuple srcNpt, dstNpt;

		srcNpt = new NodePortTuple(lt.getSrc(), lt.getSrcPort());
		dstNpt = new NodePortTuple(lt.getDst(), lt.getDstPort());

		// index it by switch source
		if (!switchLinks.containsKey(lt.getSrc()))
			switchLinks.put(lt.getSrc(),
					new HashSet<Link>());
		switchLinks.get(lt.getSrc()).add(lt);

		// index it by switch dest
		if (!switchLinks.containsKey(lt.getDst()))
			switchLinks.put(lt.getDst(),
					new HashSet<Link>());
		switchLinks.get(lt.getDst()).add(lt);

		// index both ends by switch:port
		if (!portLinks.containsKey(srcNpt))
			portLinks.put(srcNpt,
					new HashSet<Link>());
		portLinks.get(srcNpt).add(lt);

		if (!portLinks.containsKey(dstNpt))
			portLinks.put(dstNpt,
					new HashSet<Link>());
		portLinks.get(dstNpt).add(lt);

		newInfo.addObservedLatency(lt.getLatency());

		return true;
	}

	/**
	 * Determine if a link should be updated and set the time stamps if it should.
	 * Also, determine the correct latency value for the link. An existing link
	 * will have a list of latencies associated with its LinkInfo. If enough time has
	 * elapsed to determine a good latency baseline average and the new average is
	 * greater or less than the existing latency value by a set threshold, then the
	 * latency should be updated. This allows for latencies to be smoothed and reduces
	 * the number of link updates due to small fluctuations (or outliers) in instantaneous
	 * link latency values.
	 * 确定链接是否需要更新，如果需要，则设置时间戳。另外，确定链接的正确延迟值。现有链接将具有与其LinkInfo相关联的延迟列表。
	 * 如果已经经过了足够的时间来确定良好的延迟基线平均值，并且新平均值大于或小于现有的延迟值，那么应该更新延迟。
	 * 这允许延迟被平滑，并减少由于即时链接延迟值的小波动(或异常值)而导致的链接更新数量。
	 * 
	 * @param lt with observed latency. Will be replaced with latency to use.
	 * @param existingInfo with past observed latencies and time stamps
	 * @param newInfo with updated time stamps
	 * @return true if update occurred; false if no update should be dispatched
	 */
	protected boolean updateLink(@Nonnull Link lk, @Nonnull LinkInfo existingInfo, @Nonnull LinkInfo newInfo) {
		boolean linkChanged = false;
		boolean ignoreBDDP_haveLLDPalready = false;
		
		/*
		 * Check if we are transitioning from one link type to another.
		 * 检查我们是否正在从一个链接类型转换到另一个链接类型
		 * A transition is:
		 * -- going from no LLDP time to an LLDP time (is OpenFlow link)
		 * -- going from an LLDP time to a BDDP time (is non-OpenFlow link)
		 * 
		 * Note: Going from LLDP to BDDP means our LLDP link must have timed
		 * out already (null in existing LinkInfo). Otherwise, we'll flap
		 * between mulitcast and unicast links.
		 * 
		 * 如果从LLDP转到BDDP说明我们的LLDPlink已经超时，否则我们将在多播和单播之间切换
		 */
		if (existingInfo.getMulticastValidTime() == null && newInfo.getMulticastValidTime() != null) {
			if (existingInfo.getUnicastValidTime() == null) { /* unicast must be null to go to multicast */
				log.debug("Link is BDDP. Changed.");
				linkChanged = true; /* detected BDDP LLDP变为了BDDP*/
			} else {
				ignoreBDDP_haveLLDPalready = true;
			}
		} else if (existingInfo.getUnicastValidTime() == null && newInfo.getUnicastValidTime() != null) {
			log.debug("Link is LLDP. Changed.");
			linkChanged = true; /* detected LLDP 探测到了LLDP*/
		}

		/* 
		 * If we're undergoing an LLDP update (non-null time), grab the new LLDP time.
		 * 如果我们正在进行LLDP更新(非空时间)，获取新的LLDP时间。
		 * 
		 * If we're undergoing a BDDP update (non-null time), grab the new BDDP time.
		 * 
		 * Only do this if the new LinkInfo is non-null for each respective field.
		 * We want to overwrite an existing LLDP/BDDP time stamp with null if it's
		 * still valid.
		 * 只有在新的LinkInfo对于每个字段是非空的情况下才这样做。如果LLDP/BDDP时间戳仍然有效，我们希望用null覆盖它。
		 */
		if (newInfo.getUnicastValidTime() != null) {//新的为LLDP link
			existingInfo.setUnicastValidTime(newInfo.getUnicastValidTime());
		} else if (newInfo.getMulticastValidTime() != null) {//新的为BDDP link
			existingInfo.setMulticastValidTime(newInfo.getMulticastValidTime());
		}	

		/*
		 * Update Link latency if we've accumulated enough latency data points
		 * and if the average exceeds +/- the current stored latency by the
		 * defined update threshold.
		 * 如果我们积累了足够的延迟数据点，如果平均延迟超过了+/-当前存储的延迟阈值(0.5)，则更新链接延迟。
		 */
		U64 currentLatency = existingInfo.getCurrentLatency();
		U64 latencyToUse = existingInfo.addObservedLatency(lk.getLatency());

		if (currentLatency == null) {
			/* no-op; already 'changed' as this is a new link */
		} else if (!latencyToUse.equals(currentLatency) && !ignoreBDDP_haveLLDPalready) {
			log.debug("Updating link {} latency to {}ms", lk.toKeyString(), latencyToUse.getValue());
			lk.setLatency(latencyToUse);
			linkChanged = true;
		} else {
			log.trace("No need to update link latency {}", lk.toString());
		}

		return linkChanged;
	}

	protected boolean addOrUpdateLink(Link lt, LinkInfo newInfo) {
		boolean linkChanged = false;

		lock.writeLock().lock();
		try {
			/*
			 * Put the new info only if new. We want a single LinkInfo
			 * to exist per Link. This will allow us to track latencies
			 * without having to conduct a deep, potentially expensive
			 * copy each time a link is updated.
			 * 
			 * 只有在新的时候，才会put info，希望已经存在的每个link，只有一个linkInfo
			 * 这允许我们追踪时延，无需在每次更新链接时进行深入的、可能昂贵的复制
			 */
			LinkInfo existingInfo = null;
			if (links.get(lt) == null) {
				links.put(lt, newInfo); /* Only put if doesn't exist or null value */
			} else {
				existingInfo = links.get(lt);
			}

			/* Update existing LinkInfo with most recent time stamp 
			 * 对于已经存在的LinkInfo，使用最近的时间戳来进行更新
			 * */
			if (existingInfo != null && existingInfo.getFirstSeenTime().before(newInfo.getFirstSeenTime())) {
				existingInfo.setFirstSeenTime(newInfo.getFirstSeenTime());
			}

			if (log.isTraceEnabled()) {
				log.trace("addOrUpdateLink: {} {}", lt,
						(newInfo.getMulticastValidTime() != null) ? "multicast" : "unicast");
			}

			UpdateOperation updateOperation = null;
			linkChanged = false;

			if (existingInfo == null) {//为空，说明这是一个新link的linkInfo
				addLink(lt, newInfo);
				updateOperation = UpdateOperation.LINK_UPDATED;
				linkChanged = true;

				// Log direct links only. Multi-hop links may be numerous
				// Add all to event history
				//只log直连的links,多跳link可能会很多，将所有内容添加到事件历史记录中
				LinkType linkType = getLinkType(lt, newInfo);
				if (linkType == ILinkDiscovery.LinkType.DIRECT_LINK) {
					log.debug("Inter-switch link detected: {}", lt);
				}
			} else {//已经存在link info的情况下
				linkChanged = updateLink(lt, existingInfo, newInfo);//看看是否要更新时延，1是link类型变化，2是时延与历史平均时延比较超过了阈值
				if (linkChanged) {
					updateOperation = UpdateOperation.LINK_UPDATED;
					LinkType linkType = getLinkType(lt, newInfo);
					if (linkType == ILinkDiscovery.LinkType.DIRECT_LINK) {
						log.debug("Inter-switch link updated: {}", lt);
					}
				}
			}

			if (linkChanged) {//如果需要更新
				// find out if the link was added or removed here.
				updates.add(new LDUpdate(lt.getSrc(), lt.getSrcPort(),
						lt.getDst(), lt.getDstPort(),
						lt.getLatency(),
						getLinkType(lt, newInfo),
						updateOperation));
				/* Update link structure (FIXME shouldn't have to do this, since it should be the same object) */
				Iterator<Entry<Link, LinkInfo>> it = links.entrySet().iterator();
				while (it.hasNext()) {
					Entry<Link, LinkInfo> entry = it.next();
					if (entry.getKey().equals(lt)) {
						entry.getKey().setLatency(lt.getLatency());
						break;
					}
				}
			}
			
			// Write changes to storage. This will always write the updated
			// valid time, plus the port states if they've changed (i.e. if
			// they weren't set to null in the previous block of code.
			writeLinkToStorage(lt, newInfo);
			
		} finally {
			lock.writeLock().unlock();
		}

		return linkChanged;
	}

	/**
	 * Delete a link
	 *
	 * @param link
	 *            - link to be deleted.
	 * @param reason
	 *            - reason why the link is deleted.
	 */
	protected void deleteLink(Link link, String reason) {
		if (link == null)
			return;
		List<Link> linkList = new ArrayList<Link>();
		linkList.add(link);
		deleteLinks(linkList, reason);
	}
	/**
	 * Removes links from memory and storage.
	 *
	 * @param links
	 *            The List of @LinkTuple to delete.
	 */
	protected void deleteLinks(List<Link> links, String reason) {
		deleteLinks(links, reason, null);
	}

	/**
	 * Removes links from memory and storage.
	 *
	 * @param links
	 *            The List of @LinkTuple to delete.
	 */
	protected void deleteLinks(List<Link> links, String reason,
			List<LDUpdate> updateList) {

		NodePortTuple srcNpt, dstNpt;
		List<LDUpdate> linkUpdateList = new ArrayList<LDUpdate>();
		lock.writeLock().lock();
		try {
			for (Link lt : links) {
				srcNpt = new NodePortTuple(lt.getSrc(), lt.getSrcPort());
				dstNpt = new NodePortTuple(lt.getDst(), lt.getDstPort());

				if (switchLinks.containsKey(lt.getSrc())) {
					switchLinks.get(lt.getSrc()).remove(lt);
					if (switchLinks.get(lt.getSrc()).isEmpty())
						this.switchLinks.remove(lt.getSrc());
				}
				if (this.switchLinks.containsKey(lt.getDst())) {
					switchLinks.get(lt.getDst()).remove(lt);
					if (this.switchLinks.get(lt.getDst()).isEmpty())
						this.switchLinks.remove(lt.getDst());
				}

				if (this.portLinks.get(srcNpt) != null) {
					this.portLinks.get(srcNpt).remove(lt);
					if (this.portLinks.get(srcNpt).isEmpty())
						this.portLinks.remove(srcNpt);
				}
				if (this.portLinks.get(dstNpt) != null) {
					this.portLinks.get(dstNpt).remove(lt);
					if (this.portLinks.get(dstNpt).isEmpty())
						this.portLinks.remove(dstNpt);
				}

				LinkInfo info = this.links.remove(lt);
				LinkType linkType = getLinkType(lt, info);
				linkUpdateList.add(new LDUpdate(lt.getSrc(),
						lt.getSrcPort(),
						lt.getDst(),
						lt.getDstPort(),
						lt.getLatency(),
						linkType,
						UpdateOperation.LINK_REMOVED));

				// remove link from storage.
				removeLinkFromStorage(lt);

				// TODO Whenever link is removed, it has to checked if
				// the switchports must be added to quarantine.

				if (linkType == ILinkDiscovery.LinkType.DIRECT_LINK) {
					log.info("Inter-switch link removed: {}", lt);
				} else if (log.isTraceEnabled()) {
					log.trace("Deleted link {}", lt);
				}
			}
		} finally {
			if (updateList != null) linkUpdateList.addAll(updateList);
			updates.addAll(linkUpdateList);
			lock.writeLock().unlock();
		}
	}

	/**
	 * Delete links incident on a given switch port.
	 *
	 * @param npt
	 * @param reason
	 */
	protected void deleteLinksOnPort(NodePortTuple npt, String reason) {
		List<Link> eraseList = new ArrayList<Link>();
		if (this.portLinks.containsKey(npt)) {
			if (log.isTraceEnabled()) {
				log.trace("handlePortStatus: Switch {} port #{} "
						+ "removing links {}",
						new Object[] {
								npt.getNodeId().toString(),
								npt.getPortId(),
								this.portLinks.get(npt) });
			}
			eraseList.addAll(this.portLinks.get(npt));
			deleteLinks(eraseList, reason);
		}
	}

	/**
	 * Iterates through the list of links and deletes if the last discovery
	 * message reception time exceeds timeout values.
	 */
	protected void timeoutLinks() {
		List<Link> eraseList = new ArrayList<Link>();
		Long curTime = System.currentTimeMillis();
		boolean unicastTimedOut = false;
		
		/* Reentrant required here because deleteLink also write locks. */
		lock.writeLock().lock();
		try {
			Iterator<Entry<Link, LinkInfo>> it = this.links.entrySet().iterator();
			while (it.hasNext()) {
				Entry<Link, LinkInfo> entry = it.next();
				Link lt = entry.getKey();
				LinkInfo info = entry.getValue();

				/* Timeout the unicast and multicast LLDP valid times independently. */
				if ((info.getUnicastValidTime() != null)
						&& (info.getUnicastValidTime().getTime()
								+ (this.LINK_TIMEOUT * 1000) < curTime)) {
					unicastTimedOut = true;
					info.setUnicastValidTime(null);
				}
				if ((info.getMulticastValidTime() != null)
						&& (info.getMulticastValidTime().getTime()
								+ (this.LINK_TIMEOUT * 1000) < curTime)) {
					info.setMulticastValidTime(null);
				}
				/* 
				 * Add to the erase list only if the unicast time is null
				 * and the multicast time is null as well. Otherwise, if
				 * only the unicast time is null and we just set it to 
				 * null (meaning it just timed out), then we transition
				 * from unicast to multicast.
				 */
				if (info.getUnicastValidTime() == null 
						&& info.getMulticastValidTime() == null) {
					eraseList.add(entry.getKey());
				} else if (unicastTimedOut) {
					/* Just moved from unicast to multicast. */
					updates.add(new LDUpdate(lt.getSrc(), lt.getSrcPort(),
							lt.getDst(), lt.getDstPort(), lt.getLatency(),
							getLinkType(lt, info),
							UpdateOperation.LINK_UPDATED));
				}
			}

			if (!eraseList.isEmpty()) {
				deleteLinks(eraseList, "LLDP timeout");
			}
		} finally {
			lock.writeLock().unlock();
		}
	}

	//******************
	// Internal Helper Methods
	//******************
	protected void setControllerTLV() {
		// Setting the controllerTLVValue based on current nano time,
		// controller's IP address, and the network interface object hash
		// the corresponding IP address.

		final int prime = 7867;

		byte[] controllerTLVValue = new byte[] { 0, 0, 0, 0, 0, 0, 0, 0 }; // 8
		// byte
		// value.
		ByteBuffer bb = ByteBuffer.allocate(10);

		long result = System.nanoTime();
		try{
			// Use some data specific to the machine this controller is
			// running on. In this case: the list of network interfaces
			Enumeration<NetworkInterface> ifaces =
					NetworkInterface.getNetworkInterfaces();
			if (ifaces != null) {
				result = result * prime + ifaces.hashCode();
			}
		} catch (SocketException e) {
			log.warn("Could not get list of interfaces of local machine to " +
					"encode in TLV: {}", e.toString());
		}
		// set the first 4 bits to 0.
		result = result & (0x0fffffffffffffffL);

		bb.putLong(result);

		bb.rewind();
		bb.get(controllerTLVValue, 0, 8);

		this.controllerTLV = new LLDPTLV().setType((byte) 0x0c)
				.setLength((short) controllerTLVValue.length)
				.setValue(controllerTLVValue);
	}

	//******************
	// IOFSwitchListener
	//******************
	private void handlePortDown(DatapathId switchId, OFPort portNumber) {
		NodePortTuple npt = new NodePortTuple(switchId, portNumber);
		deleteLinksOnPort(npt, "Port Status Changed");
		LDUpdate update = new LDUpdate(switchId, portNumber,
				UpdateOperation.PORT_DOWN);
		updates.add(update);
	}
	/**
	 * We don't react the port changed notifications here. we listen for
	 * OFPortStatus messages directly. Might consider using this notifier
	 * instead
	 */
	@Override
	public void switchPortChanged(DatapathId switchId,
			OFPortDesc port,
			PortChangeType type) {

		switch (type) {
		case UP:
			processNewPort(switchId, port.getPortNo());
			break;
		case DELETE: case DOWN:
			handlePortDown(switchId, port.getPortNo());
			break;
		case OTHER_UPDATE: case ADD:
			// This is something other than port add or delete.
			// Topology does not worry about this.
			// If for some reason the port features change, which
			// we may have to react.
			break;
		}
	}

	@Override
	public void switchAdded(DatapathId switchId) {
		// no-op
		// We don't do anything at switch added, but we do only when the
		// switch is activated.
	}

	@Override
	public void switchRemoved(DatapathId sw) {
		List<Link> eraseList = new ArrayList<Link>();
		lock.writeLock().lock();
		try {
			if (switchLinks.containsKey(sw)) {
				if (log.isTraceEnabled()) {
					log.trace("Handle switchRemoved. Switch {}; removing links {}", sw.toString(), switchLinks.get(sw));
				}

				List<LDUpdate> updateList = new ArrayList<LDUpdate>();
				updateList.add(new LDUpdate(sw, SwitchType.BASIC_SWITCH, UpdateOperation.SWITCH_REMOVED));
				// add all tuples with an endpoint on this switch to erase list
				eraseList.addAll(switchLinks.get(sw));

				// Sending the updateList, will ensure the updates in this
				// list will be added at the end of all the link updates.
				// Thus, it is not necessary to explicitly add these updates
				// to the queue.
				deleteLinks(eraseList, "Switch Removed", updateList);
			} else {
				// Switch does not have any links.
				updates.add(new LDUpdate(sw, SwitchType.BASIC_SWITCH, UpdateOperation.SWITCH_REMOVED));
			}
		} finally {
			lock.writeLock().unlock();
		}

	}


	@Override
	public void switchActivated(DatapathId switchId) {
		IOFSwitch sw = switchService.getSwitch(switchId);
		if (sw == null)       //fix dereference violation in case race conditions
			return;
		if (sw.getEnabledPortNumbers() != null) {
			for (OFPort p : sw.getEnabledPortNumbers()) {
				processNewPort(sw.getId(), p);
			}
		}
		LDUpdate update = new LDUpdate(sw.getId(), SwitchType.BASIC_SWITCH, UpdateOperation.SWITCH_UPDATED);
		updates.add(update);
	}

	@Override
	public void switchChanged(DatapathId switchId) {
		// no-op
	}


	//*********************
	//   Storage Listener
	//*********************
	/**
	 * Sets the IStorageSource to use for Topology
	 *
	 * @param storageSource
	 *            the storage source to use
	 */
	public void setStorageSource(IStorageSourceService storageSourceService) {
		this.storageSourceService = storageSourceService;
	}

	/**
	 * Gets the storage source for this ITopology
	 *
	 * @return The IStorageSource ITopology is writing to
	 */
	public IStorageSourceService getStorageSource() {
		return storageSourceService;
	}

	@Override
	public void rowsModified(String tableName, Set<Object> rowKeys) {

		if (tableName.equals(TOPOLOGY_TABLE_NAME)) {
			readTopologyConfigFromStorage();
			return;
		}
	}

	@Override
	public void rowsDeleted(String tableName, Set<Object> rowKeys) {
		// Ignore delete events, the switch delete will do the
		// right thing on it's own.
		readTopologyConfigFromStorage();
	}


	//******************************
	// Internal methods - Config Related
	//******************************

	protected void readTopologyConfigFromStorage() {
		IResultSet topologyResult = storageSourceService.executeQuery(TOPOLOGY_TABLE_NAME,
				null, null,
				null);

		if (topologyResult.next()) {
			boolean apf = topologyResult.getBoolean(TOPOLOGY_AUTOPORTFAST);
			autoPortFastFeature = apf;
		} else {
			this.autoPortFastFeature = AUTOPORTFAST_DEFAULT;
		}

		if (autoPortFastFeature)
			log.debug("Setting autoportfast feature to ON");
		else
			log.debug("Setting autoportfast feature to OFF");
	}

	/**
	 * Deletes all links from storage
	 */
	void clearAllLinks() {
		storageSourceService.deleteRowsAsync(LINK_TABLE_NAME, null);
	}

	/**
	 * Writes a LinkTuple and corresponding LinkInfo to storage
	 *
	 * @param lt
	 *            The LinkTuple to write
	 * @param linkInfo
	 *            The LinkInfo to write
	 */
	protected void writeLinkToStorage(Link lt, LinkInfo linkInfo) {
		LinkType type = getLinkType(lt, linkInfo);

		// Write only direct links. Do not write links to external
		// L2 network.
		// if (type != LinkType.DIRECT_LINK && type != LinkType.TUNNEL) {
		// return;
		// }

		Map<String, Object> rowValues = new HashMap<String, Object>();
		String id = getLinkId(lt);
		rowValues.put(LINK_ID, id);
		rowValues.put(LINK_VALID_TIME, linkInfo.getUnicastValidTime());
		String srcDpid = lt.getSrc().toString();
		rowValues.put(LINK_SRC_SWITCH, srcDpid);
		rowValues.put(LINK_SRC_PORT, lt.getSrcPort());

		if (type == LinkType.DIRECT_LINK)
			rowValues.put(LINK_TYPE, "internal");
		else if (type == LinkType.MULTIHOP_LINK)
			rowValues.put(LINK_TYPE, "external");
		else if (type == LinkType.TUNNEL)
			rowValues.put(LINK_TYPE, "tunnel");
		else
			rowValues.put(LINK_TYPE, "invalid");

		String dstDpid = lt.getDst().toString();
		rowValues.put(LINK_DST_SWITCH, dstDpid);
		rowValues.put(LINK_DST_PORT, lt.getDstPort());

		storageSourceService.updateRowAsync(LINK_TABLE_NAME, rowValues);
	}

	/**
	 * Removes a link from storage using an asynchronous call.
	 *
	 * @param lt
	 *            The LinkTuple to delete.
	 */
	protected void removeLinkFromStorage(Link lt) {
		String id = getLinkId(lt);
		storageSourceService.deleteRowAsync(LINK_TABLE_NAME, id);
	}

	public Long readLinkValidTime(Link lt) {
		// FIXME: We're not currently using this right now, but if we start
		// to use this again, we probably shouldn't use it in its current
		// form, because it's doing synchronous storage calls. Depending
		// on the context this may still be OK, but if it's being called
		// on the packet in processing thread it should be reworked to
		// use asynchronous storage calls.
		Long validTime = null;
		IResultSet resultSet = null;
		try {
			String[] columns = { LINK_VALID_TIME };
			String id = getLinkId(lt);
			resultSet = storageSourceService.executeQuery(LINK_TABLE_NAME,
					columns,
					new OperatorPredicate(
							LINK_ID,
							OperatorPredicate.Operator.EQ,
							id),
							null);
			if (resultSet.next())
				validTime = resultSet.getLong(LINK_VALID_TIME);
		} finally {
			if (resultSet != null) resultSet.close();
		}
		return validTime;
	}

	/**
	 * Gets the storage key for a LinkTuple
	 *
	 * @param lt
	 *            The LinkTuple to get
	 * @return The storage key as a String
	 */
	private String getLinkId(Link lt) {
		return lt.getSrc().toString() + "-" + lt.getSrcPort()
				+ "-" + lt.getDst().toString() + "-"
				+ lt.getDstPort();
	}

	//***************
	// IFloodlightModule
	//***************

	//这里添加了其实现的接口
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ILinkDiscoveryService.class);
		// l.add(ITopologyService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
		// We are the class that implements the service
		m.put(ILinkDiscoveryService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IStorageSourceService.class);
		l.add(IThreadPoolService.class);
		l.add(IRestApiService.class);
		l.add(IShutdownService.class);
		return l;
	}

	//初始化
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		//获得各个服务类接口的实现模块
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		storageSourceService = context.getServiceImpl(IStorageSourceService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
		debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		shutdownService = context.getServiceImpl(IShutdownService.class);

		// read our config options
		Map<String, String> configOptions = context.getConfigParams(this);
		try {
			String histSize = configOptions.get("event-history-size");
			if (histSize != null) {
				EVENT_HISTORY_SIZE = Short.parseShort(histSize);
			}
		} catch (NumberFormatException e) {
			log.warn("Error event history size. Using default of {} seconds", EVENT_HISTORY_SIZE);
		}
		log.debug("Event history size set to {}", EVENT_HISTORY_SIZE);

		try {
			String latencyHistorySize = configOptions.get("latency-history-size");
			if (latencyHistorySize != null) {
				LATENCY_HISTORY_SIZE = Integer.parseInt(latencyHistorySize);
			}
		} catch (NumberFormatException e) {
			log.warn("Error in latency history size. Using default of {} LLDP intervals", LATENCY_HISTORY_SIZE);
		}
		log.info("Link latency history set to {} LLDP data points", LATENCY_HISTORY_SIZE, LATENCY_HISTORY_SIZE);

		try {
			String latencyUpdateThreshold = configOptions.get("latency-update-threshold");
			if (latencyUpdateThreshold != null) {
				LATENCY_UPDATE_THRESHOLD = Double.parseDouble(latencyUpdateThreshold);
			}
		} catch (NumberFormatException e) {
			log.warn("Error in latency update threshold. Can be from 0 to 1.", LATENCY_UPDATE_THRESHOLD);
		}
		log.info("Latency update threshold set to +/-{} ({}%) of rolling historical average", LATENCY_UPDATE_THRESHOLD, LATENCY_UPDATE_THRESHOLD * 100);

		// Set the autoportfast feature to false.
		this.autoPortFastFeature = AUTOPORTFAST_DEFAULT;

		// We create this here because there is no ordering guarantee
		this.linkDiscoveryAware = new ArrayList<ILinkDiscoveryListener>();
		this.lock = new ReentrantReadWriteLock();
		this.updates = new LinkedBlockingQueue<LDUpdate>();
		this.links = new HashMap<Link, LinkInfo>();
		this.portLinks = new HashMap<NodePortTuple, Set<Link>>();
		this.suppressLinkDiscovery = Collections.synchronizedSet(new HashSet<NodePortTuple>());
		this.switchLinks = new HashMap<DatapathId, Set<Link>>();
		this.quarantineQueue = new LinkedBlockingQueue<NodePortTuple>();
		this.maintenanceQueue = new LinkedBlockingQueue<NodePortTuple>();
		this.toRemoveFromQuarantineQueue = new LinkedBlockingQueue<NodePortTuple>();
		this.toRemoveFromMaintenanceQueue = new LinkedBlockingQueue<NodePortTuple>();

		this.ignoreMACSet = Collections.newSetFromMap(
				new ConcurrentHashMap<MACRange,Boolean>());
		this.haListener = new HAListenerDelegate();
		this.floodlightProviderService.addHAListener(this.haListener);
		
		this.lldpSendCounter = new HashMap<NodePortTuple,Long>();
		this.lldpReceiveCounter = new HashMap<NodePortTuple,Long>();
		this.lldpTimeStampCounter = new HashMap<NodePortTuple,Map<Long,Integer>>();
		
		this.bddpSendCounter = new HashMap<NodePortTuple,Long>();
		this.bddpReceiveCounter = new HashMap<NodePortTuple,Long>();
		this.bddpTimeStampCounter = new HashMap<NodePortTuple,Map<Long,Integer>>();
		
		registerLinkDiscoveryDebugCounters();
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {

		// Initialize role to floodlight provider role.
		this.role = floodlightProviderService.getRole();

		// Create our storage tables
		if (storageSourceService == null) {
			log.error("No storage source found.");
			return;
		}

		storageSourceService.createTable(TOPOLOGY_TABLE_NAME, null);
		storageSourceService.setTablePrimaryKeyName(TOPOLOGY_TABLE_NAME,
				TOPOLOGY_ID);
		readTopologyConfigFromStorage();

		storageSourceService.createTable(LINK_TABLE_NAME, null);
		storageSourceService.setTablePrimaryKeyName(LINK_TABLE_NAME, LINK_ID);
		storageSourceService.deleteMatchingRows(LINK_TABLE_NAME, null);
		// Register for storage updates for the switch table
		try {
			storageSourceService.addListener(SWITCH_CONFIG_TABLE_NAME, this);
			storageSourceService.addListener(TOPOLOGY_TABLE_NAME, this);
		} catch (StorageException ex) {
			log.error("Error in installing listener for "
					+ "switch table {}", SWITCH_CONFIG_TABLE_NAME);
		}

		ScheduledExecutorService ses = threadPoolService.getScheduledExecutor();

		// To be started by the first switch connection 由第一个switch连接启动
		discoveryTask = new SingletonTask(ses, new Runnable() {
			@Override
			public void run() {
				try {
					if (role == null || role == HARole.ACTIVE) { /* don't send if we just transitioned to STANDBY */
					    discoverLinks();
					}
				} catch (StorageException e) {
					shutdownService.terminate("Storage exception in LLDP send timer. Terminating process " + e, 0);
				} catch (Exception e) {
					log.error("Exception in LLDP send timer.", e);
				} finally {
					if (!shuttingDown) {
						// null role implies HA mode is not enabled.
						if (role == null || role == HARole.ACTIVE) {
							log.trace("Rescheduling discovery task as role = {}",
									role);
							discoveryTask.reschedule(DISCOVERY_TASK_INTERVAL,
									TimeUnit.SECONDS);
						} else {
							log.trace("Stopped LLDP rescheduling due to role = {}.",
									role);
						}
					}
				}
			}
		});

		// null role implies HA mode is not enabled.
		if (role == null || role == HARole.ACTIVE) {
			log.trace("Setup: Rescheduling discovery task. role = {}", role);
			discoveryTask.reschedule(DISCOVERY_TASK_INTERVAL,
					TimeUnit.SECONDS);
		} else {
			log.trace("Setup: Not scheduling LLDP as role = {}.", role);
		}

		// Setup the BDDP task. It is invoked whenever switch port tuples
		// are added to the quarantine list.
		//启动BDDP任务，它在交换机端口元组被添加到隔离list的时候调用
		bddpTask = new SingletonTask(ses, new QuarantineWorker());
		//BDDP_TASK_INTERVAL=100ms
		bddpTask.reschedule(BDDP_TASK_INTERVAL, TimeUnit.MILLISECONDS);

		updatesThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						doUpdatesThread();
					} catch (InterruptedException e) {
						return;
					}
				}
			}
		}, "Topology Updates");
		updatesThread.start();

		// Register for the OpenFlow messages we want to receive
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addOFMessageListener(OFType.PORT_STATUS, this);
		// Register for switch updates
		switchService.addOFSwitchListener(this);
		floodlightProviderService.addHAListener(this.haListener);
		floodlightProviderService.addInfoProvider("summary", this);
		if (restApiService != null)
			restApiService.addRestletRoutable(new LinkDiscoveryWebRoutable());
		setControllerTLV();
	}

	// ****************************************************
	// Link Discovery DebugCounters and DebugEvents
	// ****************************************************

	private void registerLinkDiscoveryDebugCounters() throws FloodlightModuleException {
		if (debugCounterService == null) {
			log.error("Debug Counter Service not found.");
		}
		debugCounterService.registerModule(PACKAGE);
		ctrIncoming = debugCounterService.registerCounter(PACKAGE, "incoming",
				"All incoming packets seen by this module");
		ctrLldpEol  = debugCounterService.registerCounter(PACKAGE, "lldp-eol",
				"End of Life for LLDP packets");
		ctrLinkLocalDrops = debugCounterService.registerCounter(PACKAGE, "linklocal-drops",
				"All link local packets dropped by this module");
		ctrIgnoreSrcMacDrops = debugCounterService.registerCounter(PACKAGE, "ignore-srcmac-drops",
				"All packets whose srcmac is configured to be dropped by this module");
		ctrQuarantineDrops = debugCounterService.registerCounter(PACKAGE, "quarantine-drops",
				"All packets arriving on quarantined ports dropped by this module", IDebugCounterService.MetaData.WARN);
		counterPacketOut = debugCounterService.registerCounter(PACKAGE, "packet-outs-written",
				"Packet outs written by the LinkDiscovery", IDebugCounterService.MetaData.WARN);
	}

	//*********************
	//  IInfoProvider
	//*********************

	@Override
	public Map<String, Object> getInfo(String type) {
		if (!"summary".equals(type)) return null;

		Map<String, Object> info = new HashMap<String, Object>();

		int numDirectLinks = 0;
		for (Set<Link> links : switchLinks.values()) {
			for (Link link : links) {
				LinkInfo linkInfo = this.getLinkInfo(link);
				if (linkInfo != null &&
						linkInfo.getLinkType() == LinkType.DIRECT_LINK) {
					numDirectLinks++;
				}
			}
		}
		info.put("# inter-switch links", numDirectLinks / 2);
		info.put("# quarantine ports", quarantineQueue.size());
		return info;
	}

	//***************
	// IHAListener
	//***************

	private class HAListenerDelegate implements IHAListener {
		@Override
		public void transitionToActive() {
			log.warn("Sending LLDPs due to HA change from STANDBY->ACTIVE");
			LinkDiscoveryManager.this.role = HARole.ACTIVE;
			clearAllLinks();
			readTopologyConfigFromStorage();
			log.debug("Role Change to Master: Rescheduling discovery tasks");
			discoveryTask.reschedule(1, TimeUnit.MICROSECONDS);
		}

		@Override
		public void controllerNodeIPsChanged(Map<String, String> curControllerNodeIPs,
				Map<String, String> addedControllerNodeIPs,
				Map<String, String> removedControllerNodeIPs) {
			// ignore
		}

		@Override
		public String getName() {
			return MODULE_NAME;
		}

		@Override
		public boolean isCallbackOrderingPrereq(HAListenerTypeMarker type,
				String name) {
			return false;
		}

		@Override
		public boolean isCallbackOrderingPostreq(HAListenerTypeMarker type,
				String name) {
			return "tunnelmanager".equals(name);
		}

		@Override
		public void transitionToStandby() {
            log.warn("Disabling LLDPs due to HA change from ACTIVE->STANDBY");
            LinkDiscoveryManager.this.role = HARole.STANDBY;
		}
	}

	@Override
	public void switchDeactivated(DatapathId switchId) { }
}