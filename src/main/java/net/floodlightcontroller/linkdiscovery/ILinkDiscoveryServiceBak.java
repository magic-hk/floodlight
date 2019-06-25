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

package net.floodlightcontroller.linkdiscovery;

import java.util.Map;
import java.util.Set;

import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFPort;

import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.linkdiscovery.internal.LinkInfo;


public interface ILinkDiscoveryServiceBak extends IFloodlightService {

    /**
     * Returns if a given switchport is a tunnel endpoint or not
     * 判断给定的switchport是否是一个信道端口
     */
    public boolean isTunnelPort(DatapathId sw, OFPort port);

    /**
     * Retrieves a map of all known link connections between OpenFlow switches
     * and the associated info (valid time, port states) for the link.
     * 获取所有已知的OpenFlow交换机间的连接和其相关信息(有效时间、端口状态)之间的映射。
     */
    public Map<Link, LinkInfo> getLinks();

    /**
     * Retrieves the link info for a given link
     * @param link link for which the link info should be returned
     * @return the link info for the given link
     * 获取给定link的link info
     */
    public LinkInfo getLinkInfo(Link link);

    /**
     * Returns link type of a given link
     * @param info
     * @return
     * 返回给定的link类型
     */
    public ILinkDiscovery.LinkType getLinkType(Link lt, LinkInfo info);

    /**
     * Returns OFPacketOut which contains the LLDP data corresponding
     * to switchport (sw, port). PacketOut does not contain actions.
     * PacketOut length includes the minimum length and data length.
     * 返回OFPacketOut,其包含了关联了switchport(sw,port)的LLDP数据
     * packetout不包含动作。
     * packetout长度包括最小长度和数据长度
     */
    public OFPacketOut generateLLDPMessage(IOFSwitch iofSwitch, OFPort port,
                                           boolean isStandard,
                                           boolean isReverse);

    /**
     * Returns an unmodifiable map from switch id to a set of all links with it
     * as an endpoint.
     * 返回一个无法改变的map，其从switch id映射到将其作为一个端点的所有link的集合
     */
    public Map<DatapathId, Set<Link>> getSwitchLinks();

    /**
     * Adds a listener to listen for ILinkDiscoveryService messages
     * @param listener The listener that wants the notifications
     * 添加一个监听器，来监听链路发现服务消息
     */
    public void addListener(ILinkDiscoveryListener listener);

    /**
     * Retrieves a set of all switch ports on which lldps are suppressed.
     * 获取所有LLDPS被压制的sw 端口
     */
    public Set<NodePortTuple> getSuppressLLDPsInfo();

    /**
     * Adds a switch port to suppress lldp set. LLDPs and BDDPs will not be sent
     * out, and if any are received on this port then they will be dropped.
     * 添加一个交换机端口来抑制lldp设置。
     * LLDPs和BDDPs不会被发送出去，如果在这个端口上收到任何，它们将被丢弃。
     */
    public void AddToSuppressLLDPs(DatapathId sw, OFPort port);

    /**
     * Removes a switch port from suppress lldp set
     * 从压制lldp集合中移除一个sw端口
     */
    public void RemoveFromSuppressLLDPs(DatapathId sw, OFPort port);

    /**
     * Get the set of quarantined ports on a switch
     * 获取一个交换机上被隔离的端口
     */
    public Set<OFPort> getQuarantinedPorts(DatapathId sw);

    /**
     * Get the status of auto port fast feature.
     * 获取auto port fast的特征的状态
     */
    public boolean isAutoPortFastFeature();

    /**
     * Set the state for auto port fast feature.
     * @param autoPortFastFeature
     * 设置auto port fast的特征的状态
     */
    public void setAutoPortFastFeature(boolean autoPortFastFeature);

    /**
     * Get the map of node-port tuples from link DB
     * 从link db中获取node-port元组
     */
    public Map<NodePortTuple, Set<Link>> getPortLinks();

    /**
     * addMACToIgnoreList is a service provided by LinkDiscovery to ignore
     * certain packets early in the packet-in processing chain. Since LinkDiscovery
     * is first in the packet-in processing pipeline, it can efficiently drop these
     * packets. Currently these packets are identified only by their source MAC address.
     * addMACToIgnoreList是LinkDiscovery提供的一个服务，用来忽略包进入处理链早期的某些数据包。
     * 
     * Add a MAC address range to ignore list. All packet ins from this range
     * will be dropped - use with care!
     * @param mac The base MAC address that is to be ignored
     * @param ignoreBits The number of LSBs to ignore. A value of 0 will add
     *        only one MAC address 'mac' to ignore list. A value of 48 will add
     *        ALL MAC addresses to the ignore list. This will cause a drop of
     *        ALL packet ins.
     */
    public void addMACToIgnoreList(MacAddress mac, int ignoreBits);
}
