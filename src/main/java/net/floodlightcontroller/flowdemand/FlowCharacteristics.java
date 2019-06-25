package net.floodlightcontroller.flowdemand;

import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IpProtocol;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.IPv6;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;

/**
 * 流特征
 * 这里要区分开流本身的特征和应用要求的约束。
 * @author hk
 *
 */
public class FlowCharacteristics {
	
	private String identity;//流标识
	
	public String getIdentity() {
		return identity;
	}
	private String etherType;//以太网类型
	private String srcMac;//源Mac地址
	private String dstMac;//目的Mac地址
	private String vlanId;//虚拟网Id
	
	private String ipProtocol;//1 ICMP 6/7UDP/TCP 
	private String transPortProtocol;//传输层协议
	private String srcIp;//src host ip
	//TCP/UDP端口
	private String srcPort;//src host port
	private String dstIp;//dst host ip
	//TCP/UDP端口
	private String dstPort;//dst host ip
	private NodePortTuple src;//src switch pid:port	
	private NodePortTuple dst;//dst switch pid:port	
	
	
	private String contenType;//流传输内容类型
	private long size;//传输内容预计大小
	private long duration;//持续时间
	private long startTime;//开始时间
	private long endTime;//结束
	
	private long maxBitrate;//最大码率
	private long avgBitrate;//平均码率
	private long minBitrate;//最小码率
	
	public String getEtherType() {
		return etherType;
	}
	public void setEtherType(String etherType) {
		this.etherType = etherType;
	}
	
	public String getSrcMac() {
		return srcMac;
	}
	public void setSrcMac(String srcMac) {
		this.srcMac = srcMac;
	}
	public String getDstMac() {
		return dstMac;
	}
	public void setDstMac(String dstMac) {
		this.dstMac = dstMac;
	}
	public String getVlanId() {
		return vlanId;
	}
	public void setVlanId(String vlanId) {
		this.vlanId = vlanId;
	}
	public String getIpProtocol() {
		return ipProtocol;
	}
	public void setIpProtocol(String ipProtocol) {
		this.ipProtocol = ipProtocol;
	}
	public String getTransPortProtocol() {
		return transPortProtocol;
	}
	public void setTransPortProtocol(String transPortProtocol) {
		this.transPortProtocol = transPortProtocol;
	}
	public String getSrcIp() {
		return srcIp;
	}
	public void setSrcIp(String srcIp) {
		this.srcIp = srcIp;
	}
	public String getSrcPort() {
		return srcPort;
	}
	public void setSrcPort(String srcPort) {
		this.srcPort = srcPort;
	}
	public String getDstIp() {
		return dstIp;
	}
	public void setDstIp(String dstIp) {
		this.dstIp = dstIp;
	}
	public String getDstPort() {
		return dstPort;
	}
	public void setDstPort(String dstPort) {
		this.dstPort = dstPort;
	}
	public NodePortTuple getSrc() {
		return src;
	}
	public void setSrc(NodePortTuple src) {
		this.src = src;
	}
	public NodePortTuple getDst() {
		return dst;
	}
	public void setDst(NodePortTuple dst) {
		this.dst = dst;
	}
	public String getContenType() {
		return contenType;
	}
	public void setContenType(String contenType) {
		this.contenType = contenType;
	}
	public long getSize() {
		return size;
	}
	public void setSize(long size) {
		this.size = size;
	}
	public long getDuration() {
		return duration;
	}
	public void setDuration(long duration) {
		this.duration = duration;
	}
	public long getStartTime() {
		return startTime;
	}
	public void setStartTime(long startTime) {
		this.startTime = startTime;
	}
	public long getEndTime() {
		return endTime;
	}
	public void setEndTime(long endTime) {
		this.endTime = endTime;
	}
	public long getMaxBitrate() {
		return maxBitrate;
	}
	public void setMaxBitrate(long maxBitrate) {
		this.maxBitrate = maxBitrate;
	}
	public long getAvgBitrate() {
		return avgBitrate;
	}
	public void setAvgBitrate(long avgBitrate) {
		this.avgBitrate = avgBitrate;
	}
	public long getMinBitrate() {
		return minBitrate;
	}
	public void setMinBitrate(long minBitrate) {
		this.minBitrate = minBitrate;
	}
	
	public static FlowCharacteristics parseFromJsonObject(JsonObject jsonCharact) throws Exception{
		Gson gson = new Gson();
		FlowCharacteristics target = gson.fromJson(jsonCharact, FlowCharacteristics.class);
		target.setIdentity();
		return target;
	}
	
	private void setIdentity() {//srcIp/dstIp/srcPort/dstPort
		StringBuilder identity = new StringBuilder("");
		if(srcIp !=null && !("").equals(srcIp)) {
			identity.append(srcIp);
		}else {
			identity.append("*");
		}
		identity.append(";");
		if(dstIp !=null && !("").equals(dstIp)) {
			identity.append(dstIp);
		}else {
			identity.append("*");
		}
		identity.append(";");
		if(srcPort !=null && !("").equals(srcPort)) {
			identity.append(srcPort);
		}else {
			identity.append("*");
		}
		identity.append(";");
		if(dstPort !=null && !("").equals(dstPort)) {
			identity.append(dstPort);
		}else {
			identity.append("*");
		}
		this.identity = identity.toString();
	}
	/**
	 * 从ether中获取到FlowCharacteristics对象
	 * @param ether
	 * @return
	 */
	public static FlowCharacteristics getInstanceFromEtherPlayload(Ethernet eth) {
		FlowCharacteristics flowCharacteristics = new FlowCharacteristics();
		flowCharacteristics.setVlanId(String.valueOf(eth.getVlanID()));
		flowCharacteristics.setSrcMac(eth.getSourceMACAddress().toString());
		flowCharacteristics.setDstMac(eth.getDestinationMACAddress().toString());
		flowCharacteristics.setEtherType(eth.getEtherType().toString());
		if (eth.getEtherType() == EthType.IPv4) { /* shallow check for equality is okay for EthType */
	            IPv4 ip = (IPv4) eth.getPayload();
	            flowCharacteristics.setSrcIp(ip.getSourceAddress().toString());
	            flowCharacteristics.setDstIp(ip.getDestinationAddress().toString());
	            if (ip.getProtocol().equals(IpProtocol.TCP)) {
	                TCP tcp = (TCP) ip.getPayload();
	                flowCharacteristics.setTransPortProtocol("0x06");
	                flowCharacteristics.setSrcPort(tcp.getSourcePort().toString());
	                flowCharacteristics.setDstPort(tcp.getDestinationPort().toString());
	            }else if (ip.getProtocol().equals(IpProtocol.UDP)) {
	            	  UDP udp = (UDP) ip.getPayload();
	            	  flowCharacteristics.setTransPortProtocol("0x11");
		              flowCharacteristics.setSrcPort(udp.getSourcePort().toString());
		              flowCharacteristics.setDstPort(udp.getDestinationPort().toString());
	            }
		}else if (eth.getEtherType() == EthType.IPv6) {
			    IPv6 ip = (IPv6) eth.getPayload();
			    flowCharacteristics.setSrcIp(ip.getSourceAddress().toString());
		        flowCharacteristics.setDstIp(ip.getDestinationAddress().toString());
	            if (ip.getNextHeader().equals(IpProtocol.TCP)) {
	                TCP tcp = (TCP) ip.getPayload();
	                flowCharacteristics.setTransPortProtocol("0x06");
	                flowCharacteristics.setSrcPort(tcp.getSourcePort().toString());
	                flowCharacteristics.setDstPort(tcp.getDestinationPort().toString());
	            }else if (ip.getNextHeader().equals(IpProtocol.UDP)) {
	            	  UDP udp = (UDP) ip.getPayload();
	            	  flowCharacteristics.setTransPortProtocol("0x11");
		              flowCharacteristics.setSrcPort(udp.getSourcePort().toString());
		              flowCharacteristics.setDstPort(udp.getDestinationPort().toString());
	            }
		}
		flowCharacteristics.setIdentity();
		return flowCharacteristics;
	}
	
}
