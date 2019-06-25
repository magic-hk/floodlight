/**::
 *    Copyright 2013, Big Switch Networks, Inc.
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

package net.floodlightcontroller.topology;

import com.google.common.collect.ImmutableSet;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.linkdiscovery.Link;
import net.floodlightcontroller.routing.BroadcastTree;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.routing.PathId;
import net.floodlightcontroller.statistics.SwitchPortBandwidth;
import net.floodlightcontroller.util.ClusterDFS;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.python.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * A representation of a network topology. Used internally by
 * {@link TopologyManager}
 */
public class TopologyInstanceBak {

    public static final short LT_SH_LINK = 1;
    public static final short LT_BD_LINK = 2;
    public static final short LT_TUNNEL  = 3;

    public static final int MAX_LINK_WEIGHT = 10000;
    public static final int MAX_PATH_WEIGHT = Integer.MAX_VALUE - MAX_LINK_WEIGHT - 1;
    public static final int PATH_CACHE_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(TopologyInstanceBak.class);

    /* Global: general switch, port, link */
    private Set<DatapathId>                 switches;
    private Map<DatapathId, Set<OFPort>>    portsWithLinks; /* only ports with links 有link的交换机端口(包含了隧道端口和广播域端口)*/
    private Map<DatapathId, Set<OFPort>>    portsPerSwitch; /* every port on the switch 每个sw上的OFPort*/
    private Set<NodePortTuple>              portsTunnel; /* all tunnel ports in topology 拓扑中的所有隧道端口元组 DPID，portID*/
    private Set<NodePortTuple>              portsBroadcastAll; /* all broadcast ports in topology 拓扑中的所有广播端口元组*/
    private Map<DatapathId, Set<OFPort>>    portsBroadcastPerSwitch; /* broadcast ports mapped per DPID 每个sw的广播端口*/
    private Set<NodePortTuple>              portsWithMoreThanTwoLinks; /* a.k.a. "broadcast domain" non-P2P ports 广播域端口，同一端口链路大于2条链路或者链接的link是非OF区域多跳链路*/
    private Map<NodePortTuple, Set<Link>>   links; /* every link in entire topology 在整个拓扑中的每一个link*/
    private Map<NodePortTuple, Set<Link>>   linksNonBcastNonTunnel; /* only non-broadcast and non-tunnel links 没有与隧道端口和广播域端口的相连的link*/
    private Map<NodePortTuple, Set<Link>>   linksExternal; /* BDDP links b/t clusters 通过广播得知的非OF连接，非OF区域多跳链路抽象出来的link*/
    private Set<Link>                       linksNonExternalInterCluster;//这个link是OFSW之间的link，但是link的两端SW分别属于不同的蔟

    /* Blocked */
    private Set<NodePortTuple>  portsBlocked;
    private Set<Link>           linksBlocked;

    /* Per-cluster */
    private Set<Cluster>                        clusters;
    private Map<DatapathId, Set<NodePortTuple>> clusterPorts; /* ports in the cluster ID */
    private Map<DatapathId, Cluster>            clusterFromSwitch; /* cluster for each switch 每个sw所在的cluster*/

    /* Per-archipelago */
    private List<Archipelago>                   archipelagos; /* connected clusters */
    private Map<Cluster, Archipelago>           archipelagoFromCluster;
    private Map<DatapathId, Set<NodePortTuple>> portsBroadcastPerArchipelago; /* broadcast ports in each archipelago ID（群岛中最小的DatapathId） */
    private Map<PathId, List<Path>>             pathcache; /* contains computed paths ordered best to worst */

    protected TopologyInstanceBak(Map<DatapathId, Set<OFPort>> portsWithLinks,
            Set<NodePortTuple> portsBlocked,
            Map<NodePortTuple, Set<Link>> linksNonBcastNonTunnel,
            Set<NodePortTuple> portsWithMoreThanTwoLinks,
            Set<NodePortTuple> portsTunnel, 
            Map<NodePortTuple, Set<Link>> links,
            Map<DatapathId, Set<OFPort>> portsPerSwitch,
            Map<NodePortTuple, Set<Link>> linksExternal) {

        this.switches = new HashSet<DatapathId>(portsWithLinks.keySet());
        this.portsWithLinks = new HashMap<DatapathId, Set<OFPort>>();
        for (DatapathId sw : portsWithLinks.keySet()) {
            this.portsWithLinks.put(sw, new HashSet<OFPort>(portsWithLinks.get(sw)));
        }

        this.portsPerSwitch = new HashMap<DatapathId, Set<OFPort>>();
        for (DatapathId sw : portsPerSwitch.keySet()) {
            this.portsPerSwitch.put(sw, new HashSet<OFPort>(portsPerSwitch.get(sw)));
        }

        this.portsBlocked = new HashSet<NodePortTuple>(portsBlocked);
        this.linksNonBcastNonTunnel = new HashMap<NodePortTuple, Set<Link>>();
        for (NodePortTuple npt : linksNonBcastNonTunnel.keySet()) {
            this.linksNonBcastNonTunnel.put(npt, new HashSet<Link>(linksNonBcastNonTunnel.get(npt)));
        }

        this.links = new HashMap<NodePortTuple, Set<Link>>();
        for (NodePortTuple npt : links.keySet()) {
            this.links.put(npt, new HashSet<Link>(links.get(npt)));
        }

        this.linksExternal = new HashMap<NodePortTuple, Set<Link>>();
        for (NodePortTuple npt : linksExternal.keySet()) {
            this.linksExternal.put(npt, new HashSet<Link>(linksExternal.get(npt)));
        }

        this.linksNonExternalInterCluster = new HashSet<Link>();
        this.archipelagos = new ArrayList<Archipelago>();

        this.portsWithMoreThanTwoLinks = new HashSet<NodePortTuple>(portsWithMoreThanTwoLinks);
        this.portsTunnel = new HashSet<NodePortTuple>(portsTunnel);

        this.linksBlocked = new HashSet<Link>();

        this.clusters = new HashSet<Cluster>();
        this.clusterFromSwitch = new HashMap<DatapathId, Cluster>();
        this.portsBroadcastAll= new HashSet<NodePortTuple>();
        this.portsBroadcastPerSwitch = new HashMap<DatapathId,Set<OFPort>>();

        this.pathcache = new HashMap<PathId, List<Path>>();

        this.portsBroadcastPerArchipelago = new HashMap<DatapathId, Set<NodePortTuple>>();

        this.archipelagoFromCluster = new HashMap<Cluster, Archipelago>();
    }

    protected void compute() {
        /*
         * Step 1: Compute clusters ignoring ports with > 2 links and 
         * blocked links.
         * 第1步：计算蔟，忽视短裤大于2个链接以及阻塞链接
         */
        identifyClusters();

        /*
         * Step 2: Associate non-blocked links within clusters to the cluster
         * in which they reside. The remaining links are inter-cluster links.
         * 第2步：将蔟中的非阻塞链接关联到它们所在的蔟。
         * 
         * 其余的链接是蔟间链接。标识cluster之间的链接
         * 主要标记linksNonExternalInterCluster，OF连接，但是2端的SW不在同一蔟内
         */
        identifyIntraClusterLinks();

        /* 
         * Step 3: Compute the archipelagos. (Def: group of conneccted clusters)
         * Each archipelago will have its own broadcast tree, chosen by running 
         * dijkstra's algorithm from the archipelago ID switch (lowest switch 
         * DPID，datapathId). We need a broadcast tree per archipelago since each 
         * archipelago is by definition isolated from all other archipelagos.
         * 第3步：计算群岛(一组相连蔟的集合)。这里只是计算了群岛没有计算广播树
         * 
         * 每个群岛都有自己的广播树，从群岛ID交换机(最低switchDPID)中进行dijkstra算法选择。
         * 我们每个群岛需要一个广播树，因为每个群岛都是孤立的所有其他群岛的定义。
         * 
         * 群岛间的link必定是linksExternal或者是linksNonExternalInterCluster
         */
        identifyArchipelagos();

        /*
         * Step 4: Use Yens algorithm to permute through each node combination
         * within each archipelago and compute multiple paths. The shortest
         * path located (i.e. first run of dijkstra's algorithm) will be used 
         * as the broadcast tree for the archipelago.
         * 第4步：使用Yens算法对每个群岛中的每个节点组合进行排列并计算多条路径。计算了每个群岛中每个节点之间的k个最短路径。
         * 因为不同群岛之间不可达，所以没有必要计算。
         * 
         * 找到的最短路径(即dijkstra算法的第一次运行)将被用作群岛的广播树（应该是后面用到）
         */
        computeOrderedPaths();

        /*
         * Step 5: Determine the broadcast ports for each archipelago. These are
         * the ports that reside on the broadcast tree computed and saved when
         * performing path-finding. These are saved into multiple data structures
         * to aid in quick lookup per archipelago, per-switch, and topology-global.
         * 第5步：确定每个群岛的广播端口。计算每个群岛的广播树
         * 这些端口在广播树上的端口，在执行路径查找的时候被计算和保存。
         * 它们被保存到多个数据结构中，以帮助快速查找每个群岛、每个交换机和拓扑全局
         */
        computeBroadcastPortsPerArchipelago();

        /*
         * Step 6: Optionally, print topology to log for added verbosity or when debugging.
         * 第6步：
         * 可选地，打印拓扑到日志中以增加冗余或调试时记录。
         */
        printTopology();
    }

    /*
     * Checks if OF port is edge port
     */
    public boolean isEdge(DatapathId sw, OFPort portId) { 
        NodePortTuple np = new NodePortTuple(sw, portId);
        if (links.get(np) == null || links.get(np).isEmpty()) {
            return true;
        }
        else {
            return false;
        }
    }   

    /*
     * Returns broadcast ports for the given DatapathId
     */
    public Set<OFPort> swBroadcastPorts(DatapathId sw) {
        if (!portsBroadcastPerSwitch.containsKey(sw) || portsBroadcastPerSwitch.get(sw) == null) {
            log.debug("Could not locate broadcast ports for switch {}", sw);
            return Collections.emptySet();
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Found broadcast ports {} for switch {}", portsBroadcastPerSwitch.get(sw), sw);
            }
            return portsBroadcastPerSwitch.get(sw);
        }
    }

    private void printTopology() {
        log.debug("-----------------Topology-----------------------");
        log.debug("All Links: {}", links);
        log.debug("Tunnel Ports: {}", portsTunnel);
        log.debug("Clusters: {}", clusters);
        log.debug("Broadcast Ports Per Node (!!): {}", portsBroadcastPerSwitch);
        log.debug("3+ Link Ports: {}", portsWithMoreThanTwoLinks);
        log.debug("Archipelagos: {}", archipelagos);
        log.debug("-----------------------------------------------");  
      
    }
    //定义内在的ClusterLinks
    private void identifyIntraClusterLinks() {
        for (DatapathId s : switches) {
            if (portsWithLinks.get(s) == null) continue;
            for (OFPort p : portsWithLinks.get(s)) {//遍历所有有link的端口
                NodePortTuple np = new NodePortTuple(s, p);
                if (linksNonBcastNonTunnel.get(np) == null) continue;//如果在非广播和隧道中找不到，则跳过
                if (isBroadcastPort(np)) continue;
                for (Link l : linksNonBcastNonTunnel.get(np)) {//这里的link肯定是OFSW之间的连接
                    if (isBlockedLink(l)) continue;//如果是阻塞的链路，跳过
                    if (isBroadcastLink(l)) continue;//如果是广播的链路，跳过
                    Cluster c1 = clusterFromSwitch.get(l.getSrc());
                    Cluster c2 = clusterFromSwitch.get(l.getDst());
                    if (c1 == c2) {//如果在同一个蔟中
                        c1.addLink(l); /* link is within cluster 在cluster内部的link*/
                    } else {
                    	//非外部Cluster,即这个link是OFSW之间的link，但是link的两端分别属于不同的蔟。
                        linksNonExternalInterCluster.add(l);
                    }
                }
            }
        }
    }

    /**
     * @author Srinivasan Ramasubramanian
     * 强连通图：
     * 指在有向图G中，如果对于每一对vi、vj，vi≠vj，从vi到vj和从vj到vi都存在路径，则称G是强连通图。
     * 各个节点可以双向互达
     * 
     * This function divides the network into clusters. Every cluster is
     * a strongly connected component. The network may contain unidirectional
     * links.  The function calls dfsTraverse for performing depth first
     * search and cluster formation.
     * 该函数将网络划分为集群。每个集群都是一个强连通组件。网络可能包含单向链接。
     * 该函数调用dfsTraverse来执行深度优先搜索和集群形成。
     * 
     * The computation of strongly connected components is based on
     * Tarjan's algorithm.  For more details, please see the Wikipedia
     * link below.
     * 计算强连通组件，基于Tarjan's算法。
     *
     * http://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm
     */
    private void identifyClusters() {
        Map<DatapathId, ClusterDFS> dfsList = new HashMap<DatapathId, ClusterDFS>();

        if (switches == null) return;

        //初始化，这里的ClusterDFS应该是用于深度优先搜索的数据结构
        for (DatapathId key : switches) {
            ClusterDFS cdfs = new ClusterDFS();
            dfsList.put(key, cdfs);
        }

        Set<DatapathId> currSet = new HashSet<DatapathId>();

        for (DatapathId sw : switches) {
            ClusterDFS cdfs = dfsList.get(sw);
            if (cdfs == null) {
                log.error("No DFS object for switch {} found.", sw);
            } else if (!cdfs.isVisited()) {//如果还没有被访问。
                dfsTraverse(0, 1, sw, dfsList, currSet);
            }
        }
    }

    /**
     * @author Srinivasan Ramasubramanian
     *
     * This algorithm computes the depth first search (DFS) traversal of the
     * switches in the network, computes the lowpoint, and creates clusters
     * (of strongly connected components).
     * 该算法计算网络交换机遍历深度优先搜索(DFS)，计算lowpoint，并创建集群(强连接组件)。
     * 
     * The computation of strongly connected components is based on
     * Tarjan's algorithm.  For more details, please see the Wikipedia
     * link below.
     * 强连通组件的计算基于Tarjan's算法
     * http://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm
     *
     * The initialization of lowpoint and the check condition for when a
     * cluster should be formed is modified as we do not remove switches that
     * are already part of a cluster.
     * lowpoint的初始化和应该何时形成集群的检查条件被修改了，因为我们没有删除已经属于集群的交换机。
     * 
     * A return value of -1 indicates that dfsTraverse failed somewhere in the middle
     * of computation.  This could happen when a switch is removed during the cluster
     * computation procedure.
     * 返回值-1表示dfstrhate在计算过程中失败。这可能发生在集群计算过程中移除switch时。
     * 
     * @param parentIndex: DFS index of the parent node
     * @param currIndex: DFS index to be assigned to a newly visited node 分配给一个新访问过节点的DFS index
     * @param currSw: ID of the current switch 当前switch的ID
     * @param dfsList: HashMap of DFS data structure for each switch 每个switch的DFS数据结构的hashmap
     * @param currSet: Set of nodes in the current cluster in formation 
     * @return long: DSF index to be used when a new node is visited
     */
    private long dfsTraverse (long parentIndex, long currIndex, DatapathId currSw,
            Map<DatapathId, ClusterDFS> dfsList, Set<DatapathId> currSet) {

        //Get the DFS object corresponding to the current switch
    	//获取关联到当前sw的DFS对象。
        ClusterDFS currDFS = dfsList.get(currSw);

        Set<DatapathId> nodesInMyCluster = new HashSet<DatapathId>();
        Set<DatapathId> myCurrSet = new HashSet<DatapathId>();

        //Assign the DFS object with right values
        //为DFS对象分配正确的属性值
        currDFS.setVisited(true);
        //设置当前DFS索引
        currDFS.setDfsIndex(currIndex);
        //设置父DFS索引
        currDFS.setParentDFSIndex(parentIndex);
        currIndex++;

        // Traverse the graph through every outgoing link.
        //通过每个输出链接遍历图形
        if (portsWithLinks.get(currSw) != null){//如果当前交换机的Link端口不为空
            for (OFPort p : portsWithLinks.get(currSw)) {//当前交换机的所有有link的端口
            	//获取当前交换机当前端口的非广播域和非隧道link
                Set<Link> lset = linksNonBcastNonTunnel.get(new NodePortTuple(currSw, p));
                if (lset == null) continue;
                for (Link l : lset) {//对于一个端口的所有link
                    DatapathId dstSw = l.getDst();//获取这个link的源和目的交换机

                    // ignore incoming links.
                    //忽视入link，这里应该只算出link
                    if (dstSw.equals(currSw)) continue;

                    // ignore if the destination is already added to
                    // another cluster
                    //忽视，如果目的交换机已经加入到其它蔟。
                    if (clusterFromSwitch.get(dstSw) != null) continue;

                    // ignore the link if it is blocked.
                    //忽视link如果它是被阻塞的
                    if (isBlockedLink(l)) continue;

                    // ignore this link if it is in broadcast domain
                    //忽视广播域中的link
                    if (isBroadcastLink(l)) continue;

                    // Get the DFS object corresponding to the dstSw
                    //获取关联到dstSw的DFS对象
                    ClusterDFS dstDFS = dfsList.get(dstSw);

                    if (dstDFS.getDfsIndex() < currDFS.getDfsIndex()) {
                        // could be a potential lowpoint
                    	//Lowpoint 当前节点或其子树能够追溯到的最早的栈中节点的次序号
                        if (dstDFS.getDfsIndex() < currDFS.getLowpoint()) {
                            currDFS.setLowpoint(dstDFS.getDfsIndex());
                        }
                    } else if (!dstDFS.isVisited()) {//如果这个节点还没有访问过
                        // make a DFS visit 对dstSw执行一个深度优先算法
                        currIndex = dfsTraverse(
                                currDFS.getDfsIndex(), 
                                currIndex, dstSw, 
                                dfsList, myCurrSet);

                        if (currIndex < 0) return -1;

                        // update lowpoint after the visit 在访问过后，更新lowpoint
                        if (dstDFS.getLowpoint() < currDFS.getLowpoint()) {
                            currDFS.setLowpoint(dstDFS.getLowpoint());
                        }

                        nodesInMyCluster.addAll(myCurrSet);
                        myCurrSet.clear();
                    }
                    // else, it is a node already visited with a higher
                    // dfs index, just ignore.
                }
            }
        }

        nodesInMyCluster.add(currSw);
        currSet.addAll(nodesInMyCluster);

        // Cluster computation.蔟计算
        // If the node's lowpoint is greater than its parent's DFS index,
        // we need to form a new cluster with all the switches in the
        // currSet.如果这个节点的lowpont 比它的父节点的DFS索引大，我们需要创建一个新蔟，包括当前所有交换机
        if (currDFS.getLowpoint() > currDFS.getParentDFSIndex()) {
            // The cluster thus far forms a strongly connected component.
            // create a new switch cluster and the switches in the current
            // set to the switch cluster.到目前为止，集群形成了一个强连接组件。创建一个新的交换机集群和当前设置的交换机集群。
            Cluster sc = new Cluster();
            for (DatapathId sw : currSet) {
                sc.add(sw);
                clusterFromSwitch.put(sw, sc);
            }
            // delete all the nodes in the current set.
            currSet.clear();
            // add the newly formed switch clusters to the cluster set.
            clusters.add(sc);
        }

        return currIndex;
    }

    public Set<NodePortTuple> getBlockedPorts() {
        return this.portsBlocked;
    }

    public Set<Link> getBlockedLinks() {
        return this.linksBlocked;
    }

    /** Returns true if a link has either one of its switch ports
     * blocked.
     * @param l
     * @return
     */
    public boolean isBlockedLink(Link l) {
        NodePortTuple n1 = new NodePortTuple(l.getSrc(), l.getSrcPort());
        NodePortTuple n2 = new NodePortTuple(l.getDst(), l.getDstPort());
        return (isBlockedPort(n1) || isBlockedPort(n2));
    }

    public boolean isBlockedPort(NodePortTuple npt) {
        return portsBlocked.contains(npt);
    }

    public boolean isTunnelPort(NodePortTuple npt) {
        return portsTunnel.contains(npt);
    }

    public boolean isTunnelLink(Link l) {
        NodePortTuple n1 = new NodePortTuple(l.getSrc(), l.getSrcPort());
        NodePortTuple n2 = new NodePortTuple(l.getDst(), l.getDstPort());
        return (isTunnelPort(n1) || isTunnelPort(n2));
    }

    public boolean isBroadcastLink(Link l) {
        NodePortTuple n1 = new NodePortTuple(l.getSrc(), l.getSrcPort());
        NodePortTuple n2 = new NodePortTuple(l.getDst(), l.getDstPort());
        return (isBroadcastPort(n1) || isBroadcastPort(n2));
    }

    public boolean isBroadcastPort(NodePortTuple npt) {
        return portsBroadcastAll.contains(npt);
    }

    private class NodeDist implements Comparable<NodeDist> {
        private final DatapathId node;
        public DatapathId getNode() {
            return node;
        }

        private final int dist;
        public int getDist() {
            return dist;
        }

        public NodeDist(DatapathId node, int dist) {
            this.node = node;
            this.dist = dist;
        }

        @Override
        public int compareTo(NodeDist o) {
            if (o.dist == this.dist) {//如果dist相同，则比较
                return (int)(this.node.getLong() - o.node.getLong());
            }
            return this.dist - o.dist;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            NodeDist other = (NodeDist) obj;
            if (!getOuterType().equals(other.getOuterType()))
                return false;
            if (node == null) {
                if (other.node != null)
                    return false;
            } else if (!node.equals(other.node))
                return false;
            return true;
        }

        @Override
        public int hashCode() {
            assert false : "hashCode not designed";
        return 42;
        }

        private TopologyInstanceBak getOuterType() {
            return TopologyInstanceBak.this;
        }
    }

    protected void identifyArchipelagos() {
        // Iterate through each external link and create/merge archipelagos based on the
        // islands that each link is connected to
    	//遍历每个外部链接并基于每个链接所连接的岛屿创建/合并群岛
        Cluster srcCluster = null;
        Cluster dstCluster = null;
        Archipelago srcArchipelago = null;
        Archipelago dstArchipelago = null;
        Set<Link> links = new HashSet<Link>();
        
        //linksExternal BDDP links b/t clusters
        //非OF区域多跳链路抽象出来的link
        for (Set<Link> linkset : linksExternal.values()) {
        	//将linkset中所有的link都添加到links中
            links.addAll(linkset);
        }
        //OFSW之间直连，但是link的2端的SW分别属于不同的蔟
        links.addAll(linksNonExternalInterCluster);

        /* Base case of 1:1 mapping b/t clusters and archipelagos 
         * 基础情况 1:1匹配 蔟以及群岛，即一个群岛里面一个蔟
         * */
        if (links.isEmpty()) {
            if (!clusters.isEmpty()) {
                clusters.forEach(c -> archipelagos.add(new Archipelago().add(c)));
            }
        } else { /* Only for two or more adjacent clusters that form archipelags 只有临近的2个或者多个蔟才能组成群岛*/
        	//同一簇内的SW应该都是直连的，且互相可达
        	//群岛之间的link必定是linksExternal(非OFlink)或者是linksNonExternalInterCluster（OFlink，但是不是强连通）
        	for (Link l : links) {
            	//找到对于的源和目的蔟
                for (Cluster c : clusters) {
                    if (c.getNodes().contains(l.getSrc())) srcCluster = c;
                    if (c.getNodes().contains(l.getDst())) dstCluster = c;
                }
                //在现有的群岛中找，有没有对应的蔟
                for (Archipelago a : archipelagos) {
                    // Is source cluster a part of an existing archipelago?
                    if (a.isMember(srcCluster)) srcArchipelago = a;
                    // Is destination cluster a part of an existing archipelago?
                    if (a.isMember(dstCluster)) dstArchipelago = a;
                }

                // Are they both found in an archipelago? If so, then merge the two.
                //如果对于源蔟和目的蔟，不在同一个群岛，则可以合并2个群岛
                if (srcArchipelago != null && dstArchipelago != null && !srcArchipelago.equals(dstArchipelago)) {
                    srcArchipelago.merge(dstArchipelago);
                    archipelagos.remove(dstArchipelago);
                    archipelagoFromCluster.put(dstCluster, srcArchipelago);
                }

                // If neither were found in an existing, then form a new archipelago.
                //如果没有找到源群岛和目的群岛，则说明源蔟和目的蔟不属于任何现有的群岛，则新建群岛
                else if (srcArchipelago == null && dstArchipelago == null) {
                    Archipelago a = new Archipelago().add(srcCluster).add(dstCluster);
                    archipelagos.add(a);
                    archipelagoFromCluster.put(srcCluster, a);
                    archipelagoFromCluster.put(dstCluster, a);
                }

                // If only one is found in an existing, then add the one not found to the existing.
                //如果只有一个找到，然后将没有找到的蔟添加到找到的群岛中
                else if (srcArchipelago != null && dstArchipelago == null) {
                    srcArchipelago.add(dstCluster);
                    archipelagoFromCluster.put(dstCluster, srcArchipelago);
                }

                else if (srcArchipelago == null && dstArchipelago != null) {
                    dstArchipelago.add(srcCluster);
                    archipelagoFromCluster.put(srcCluster, dstArchipelago);
                }

                srcCluster = null;
                dstCluster = null;
                srcArchipelago = null;
                dstArchipelago = null;
            }
        }
    }
    

    /*
     * Dijkstra that calculates destination rooted trees over the entire topology.
     * 在实体拓扑计算目的根树
     * DatapathId 64位数字，定义一条数据路径，低48位为交换机的MAC地址，而高16为由实现者定义，可以是vlanid
     * 举例：root A(DST)   									BuildPath 
     *      B		  F										src F 		dst A
     * 	   / \5	   3/ |	     nexthoplinks <B,CB>			<F,DF>
     * 	6/	|  \  /   |					  <C,AC>			   |-<D,CD>
     * A   2|   D     |5                  <D,CD>					|-<C,AC>
     *  \	|  /  \   |                   <E,CE>						 |-A	
     * 3  \	| /2    \ |					  <F,DF>
     *      C---------E
     */
    private BroadcastTree dijkstra(Map<DatapathId, Set<Link>> links, DatapathId root,
            Map<Link, Integer> linkCost,
            boolean isDstRooted) {
        HashMap<DatapathId, Link> nexthoplinks = new HashMap<DatapathId, Link>();
        //记录root到DatapathId指定的sw的cost
        HashMap<DatapathId, Integer> cost = new HashMap<DatapathId, Integer>();
        int w;
        
        //nexthoplinks和cost初始化
        for (DatapathId node : links.keySet()) {
            nexthoplinks.put(node, null);
            cost.put(node, MAX_PATH_WEIGHT);
            //log.debug("Added max cost to {}", node);
        }

        HashMap<DatapathId, Boolean> seen = new HashMap<DatapathId, Boolean>();
        //优先队列，优先队列的作用是能保证每次取出的元素都是队列中权值最小的，这里比较的是DPID
        //其通过堆实现，具体说是通过完全二叉树（complete binary tree）实现的小顶堆
        //其存储上不是有序的，但是根据指定方式来出队列，它是有序的
        //这里的话是按dist来排序
        PriorityQueue<NodeDist> nodeq = new PriorityQueue<NodeDist>();
        //向优先队列中插入元素
        nodeq.add(new NodeDist(root, 0));
        cost.put(root, 0);

        //log.debug("{}", links);
        
        //peek()获取但不删除队首元素，也就是队列中权值最小的那个元素，这里就是dist最小的那个
        //每次循环都可以得到到一个点的一个最小距离，再基于这个最小距离来进行下一个最小距离求解
        while (nodeq.peek() != null) {
        	//返回队列的头部，或null，如果队列为空
            NodeDist n = nodeq.poll();
            DatapathId cnode = n.getNode();
            int cdist = n.getDist();

            if (cdist >= MAX_PATH_WEIGHT) break;
            if (seen.containsKey(cnode)) continue;
            seen.put(cnode, true);

            //log.debug("cnode {} and links {}", cnode, links.get(cnode));
            if (links.get(cnode) == null) continue;
            for (Link link : links.get(cnode)) {//遍历cnode的到其它sw的link
                DatapathId neighbor;

                if (isDstRooted == true) {//如果是目的根化，那么邻居就是源
                    neighbor = link.getSrc();
                } else {
                    neighbor = link.getDst();
                }

                // links directed toward cnode will result in this condition
                if (neighbor.equals(cnode)) continue;

                if (seen.containsKey(neighbor)) continue;

                if (linkCost == null || linkCost.get(link) == null) {
                    w = 1;
                } else {
                    w = linkCost.get(link);
                }
                //计算到邻居的距离
                int ndist = cdist + w; // the weight of the link, always 1 in current version of floodlight.
                log.debug("Neighbor: {}", neighbor);
                log.debug("Cost: {}", cost);
                log.debug("Neighbor cost: {}", cost.get(neighbor));
                
                //测试异常
                if(cost.get(neighbor)==null) {
                	log.info("---------Dijkstra Test Start----------");
                	log.info("迪杰斯特拉异常测试 cost {}",cost);
                    log.info("迪杰斯特拉异常测试 neighbor {}",neighbor);
                    log.info("迪杰斯特拉异常测试 cost.get(neighbor) {}",cost.get(neighbor));
                    
                    
                    //显示拓扑信息
                    log.info("-----------------Topology Info Start-----------------------");
                    log.info("All Links: {}", links);
                    log.info("linksExternal: {}", linksExternal);
                    log.info("Tunnel Ports: {}", portsTunnel);
                    log.info("Clusters: {}", clusters);
                    log.info("Broadcast Ports Per Node (!!): {}", portsBroadcastPerSwitch);
                    log.info("3+ Link Ports: {}", portsWithMoreThanTwoLinks);
                    log.info("Archipelagos: {}", archipelagos);
                    log.info("-----------------Topology Info End------------------");  
                    
                    log.info("---------Dijkstra Test End----------");
                }
                
                //如果新计算的ndist的值，小于cost中记录的先前root到neighbor的cost
                if (ndist < cost.get(neighbor)) {//这里好像会抛出异常
                    cost.put(neighbor, ndist);
                    //邻居节点，通过哪个link连接到邻居节点
                    nexthoplinks.put(neighbor, link);
                    //新建NodeDist，来更新距离
                    NodeDist ndTemp = new NodeDist(neighbor, ndist);
                    // Remove an object that's already in there.
                    // Note that the comparison is based on only the node id, 注意比较只是基于node id的
                    // and not node id and distance.
                    //根据id来删除
                    nodeq.remove(ndTemp);
                    // add the current object to the queue.
                    nodeq.add(ndTemp);//添加完成后，会重新排序，保证dist最小的在队列首部
                }
            }
        }

        BroadcastTree ret = new BroadcastTree(nexthoplinks, cost);

        return ret;
    }

    /*
     * Creates a map of links and the cost associated with each link
     * 创建link和其关联cost的map
     */
    public Map<Link,Integer> initLinkCostMap() {
        Map<Link, Integer> linkCost = new HashMap<Link, Integer>();
        //隧道权重，有link的端口的数量+1
        int tunnel_weight = portsWithLinks.size() + 1;
        
        //获取路径内部指标，这个指标是单类型的，并且是从配置文件中获取的
        switch (TopologyManager.getPathMetricInternal()){
        case HOPCOUNT_AVOID_TUNNELS:
            log.debug("Using hop count with tunnel bias for metrics");
            for (NodePortTuple npt : portsTunnel) {
                if (links.get(npt) == null) {
                    continue;
                }
                for (Link link : links.get(npt)) {
                    if (link == null) {
                        continue;
                    }
                    linkCost.put(link, tunnel_weight);
                }
            }
            return linkCost;

        case HOPCOUNT:
            log.debug("Using hop count w/o tunnel bias for metrics");
            for (NodePortTuple npt : links.keySet()) {
                if (links.get(npt) == null) {
                    continue;
                }
                for (Link link : links.get(npt)) {
                    if (link == null) {
                        continue;
                    }
                    linkCost.put(link,1);
                }
            }
            return linkCost;

        case LATENCY:
            log.debug("Using latency for path metrics");
            for (NodePortTuple npt : links.keySet()) {
                if (links.get(npt) == null) {
                    continue;
                }
                for (Link link : links.get(npt)) {
                    if (link == null) {
                        continue;
                    }
                    if ((int)link.getLatency().getValue() < 0 || 
                            (int)link.getLatency().getValue() > MAX_LINK_WEIGHT) {
                        linkCost.put(link, MAX_LINK_WEIGHT);
                    } else {
                        linkCost.put(link,(int)link.getLatency().getValue());
                    }
                }
            }
            return linkCost;

        case LINK_SPEED:
            TopologyManager.statisticsService.collectStatistics(true);
            log.debug("Using link speed for path metrics");
            for (NodePortTuple npt : links.keySet()) {
                if (links.get(npt) == null) {
                    continue;
                }
                long rawLinkSpeed = 0;
                IOFSwitch s = TopologyManager.switchService.getSwitch(npt.getNodeId());
                if (s != null) {
                	 //交换机的目的端口
                    OFPortDesc p = s.getPort(npt.getPortId());
                    if (p != null) {
                        rawLinkSpeed = p.getCurrSpeed();
                    }
                }
                for (Link link : links.get(npt)) {
                    if (link == null) {
                        continue;
                    }
                    //1MBps=1000kBps=10^6Bps=8×10^6bps
                    if ((rawLinkSpeed / 10^6) / 8 > 1) {
                        int linkSpeedMBps = (int)(rawLinkSpeed / 10^6) / 8;
                        linkCost.put(link, (1/linkSpeedMBps)*1000);
                    } else {
                        linkCost.put(link, MAX_LINK_WEIGHT);
                    }
                }
            }
            return linkCost;
            
        case UTILIZATION: //利用率
            TopologyManager.statisticsService.collectStatistics(true);
            log.debug("Using utilization for path metrics");
            for (NodePortTuple npt : links.keySet()) {
                if (links.get(npt) == null) continue;
                SwitchPortBandwidth spb = TopologyManager.statisticsService
                        .getBandwidthConsumption(npt.getNodeId(), npt.getPortId());
                long bpsTx = 0;
                if (spb != null) {
                    bpsTx = spb.getBitsPerSecondTx().getValue();
                }
                for (Link link : links.get(npt)) {
                    if (link == null) {
                        continue;
                    }

                    if ((bpsTx / 10^6) / 8 > 1) {
                        int cost = (int) (bpsTx / 10^6) / 8;
                        linkCost.put(link, cost);
                    } else {
                        linkCost.put(link, MAX_LINK_WEIGHT);
                    }
                }
            }
            return linkCost;

        default:
            log.debug("Invalid Selection: Using Default Hop Count with Tunnel Bias for Metrics");
            for (NodePortTuple npt : portsTunnel) {
                if (links.get(npt) == null) continue;
                for (Link link : links.get(npt)) {
                    if (link == null) continue;
                    linkCost.put(link, tunnel_weight);
                }
            }
            return linkCost;
        }
    }

    /*
     * Calculates and stores n possible paths  using Yen's algorithm,
     * looping through every switch. These lists of routes are stored 
     * in the pathcache.
     * 计算存储n种可能的路径，使用Yen's算法，遍历所有的交换机，这些路由的list被存储在pathcache中-
     */
    private void computeOrderedPaths() {
        List<Path> paths;
        PathId pathId;
        pathcache.clear();
        //对于每个群岛
        for (Archipelago a : archipelagos) { /* for each archipelago */
        	//源交换机
            Set<DatapathId> srcSws = a.getSwitches();
            //目的交换机
            Set<DatapathId> dstSws = a.getSwitches();
            log.debug("SRC {}", srcSws);
            log.debug("DST {}", dstSws);
            //对于每一个源和目的交换机计算k条最短路径，保存在缓存中
            for (DatapathId src : srcSws) { /* permute all member switches */
                for (DatapathId dst : dstSws) {
                    log.debug("Calling Yens {} {}", src, dst);
                    paths = yens(src, dst, TopologyManager.getMaxPathsToComputeInternal(),
                            getArchipelago(src), getArchipelago(dst));
                    pathId = new PathId(src, dst);
                    pathcache.put(pathId, paths);
                    log.debug("Adding paths {}", paths);
                }
            }
        }
    }

    private Path buildPath(PathId id, BroadcastTree tree) {
        NodePortTuple npt;
        //源交换机id
        DatapathId srcId = id.getSrc();
        //目的交换机id
        DatapathId dstId = id.getDst();
        
        //set of NodePortTuples on the route 路由上的NodePort元组
        LinkedList<NodePortTuple> sPorts = new LinkedList<NodePortTuple>();

        if (tree == null) return new Path(id, ImmutableList.of()); /* empty route */

        Map<DatapathId, Link> nexthoplinks = tree.getLinks();

        if (!switches.contains(srcId) || !switches.contains(dstId)) {//如果源和目的sw不存在
            // This is a switch that is not connected to any other switch
            // hence there was no update for links (and hence it is not
            // in the network)
            log.debug("buildpath: Standalone switch: {}", srcId);

            // The only possible non-null path for this case is
            // if srcId equals dstId --- and that too is an 'empty' path []

        } else if ((nexthoplinks != null) && (nexthoplinks.get(srcId) != null)) {
            while (!srcId.equals(dstId)) {
                Link l = nexthoplinks.get(srcId);
                npt = new NodePortTuple(l.getSrc(), l.getSrcPort());
                sPorts.addLast(npt);
                npt = new NodePortTuple(l.getDst(), l.getDstPort());
                sPorts.addLast(npt);
                srcId = nexthoplinks.get(srcId).getDst();
            }
        }
        // else, no path exists, and path equals null

        Path result = null;
        if (sPorts != null && !sPorts.isEmpty()) {
            result = new Path(id, sPorts);

        }
        log.trace("buildpath: {}", result);
        return result == null ? new Path(id, ImmutableList.of()) : result;
    }

    /*
     * Getter Functions
     */

    public boolean pathExists(DatapathId srcId, DatapathId dstId) {
        Archipelago srcA = getArchipelago(srcId);
        Archipelago dstA = getArchipelago(dstId);
        if (!srcA.getId().equals(dstA.getId())) {
            return false;
        }

        BroadcastTree bt = srcA.getBroadcastTree();
        if (bt == null) {
            return false;
        }

        Link link = bt.getLinks().get(srcId);
        if (link == null) {
            return false;
        }
        return true;
    }
    
    //建立switches的，对应Link集合映射关系
    private Map<DatapathId, Set<Link>> buildLinkDpidMap(Set<DatapathId> switches, Map<DatapathId,
            Set<OFPort>> portsWithLinks, Map<NodePortTuple, Set<Link>> links) {

        Map<DatapathId, Set<Link>> linkDpidMap = new HashMap<DatapathId, Set<Link>>();
        for (DatapathId s : switches) {
            if (portsWithLinks.get(s) == null) continue;//有的sw没有端口有link和其它sw相连接，对于这种sw，直接跳过
            for (OFPort p : portsWithLinks.get(s)) {//遍历指定sw的有link相连的所有端口
                NodePortTuple np = new NodePortTuple(s, p);//新建node,port元组
                if (links.get(np) == null) continue;//如果在传入的links中没有对应的link跳过。
                for (Link l : links.get(np)) {
                    if (switches.contains(l.getSrc()) && switches.contains(l.getDst())) {//判断sw存在
                        if (linkDpidMap.containsKey(s)) {//如果已经在map中存在了，添加新发现的link到set中
                            linkDpidMap.get(s).add(l);
                        } else {
                            linkDpidMap.put(s, new HashSet<Link>(Arrays.asList(l)));
                        }
                    }
                }
            }
        }

        return linkDpidMap;
    }

    /**
     *
     * This function returns K number of routes between a source and destination IF THEY EXIST IN THE ROUTECACHE.
     * If the user requests more routes than available, only the routes already stored in memory will be returned.
     * This value can be adjusted in floodlightdefault.properties.
     *
     *
     * @param src: DatapathId of the route source.
     * @param dst: DatapathId of the route destination.
     * @param k: The number of routes that you want. Must be positive integer.
     * @return ArrayList of Routes or null if bad parameters
     */
    public List<Path> getPathsFast(DatapathId src, DatapathId dst, int k) {
        PathId routeId = new PathId(src, dst);
        List<Path> routes = pathcache.get(routeId);

        if (routes == null || k < 1) {
            return ImmutableList.of();
        }

        if (k >= TopologyManager.getMaxPathsToComputeInternal() || k >= routes.size()) {
            return routes;
        } else {
            return routes.subList(0, k);
        }
    }

    /**
     *
     * This function returns K number of paths between a source and destination. It will attempt to retrieve
     * these paths from the pathcache. If the user requests more paths than are stored, Yen's algorithm will be
     * run using the K value passed in.
     *
     *
     * @param src: DatapathId of the path source.
     * @param dst: DatapathId of the path destination.
     * @param k: The number of path that you want. Must be positive integer.
     * @return list of paths or empty
     */
    public List<Path> getPathsSlow(DatapathId src, DatapathId dst, int k) {
        PathId pathId = new PathId(src, dst);
        List<Path> paths = pathcache.get(pathId);

        if (paths == null || k < 1) return ImmutableList.of();

        if (k >= TopologyManager.getMaxPathsToComputeInternal() || k >= paths.size()) {
            return yens(src, dst, k, getArchipelago(src), getArchipelago(dst)); /* heavy computation */
        }
        else {
            return new ArrayList<Path>(paths.subList(0, k));
        }
    }

    private Archipelago getArchipelago(DatapathId d) {
        for (Archipelago a : archipelagos) {
            if (a.getSwitches().contains(d)) {
                return a;
            }
        }
        //判断导致空指针的DatapathId
        log.info("-------------------HK TEST START-------------------");
        log.info("getArchipelagoTest miss hit sw {}",d);
        log.info("-------------------HK TEST END-------------------");
        return null;
    }

    public void setPathCosts(Path p) {
        U64 cost = U64.ZERO;

        // Set number of hops. Assuming the list of NPTs is always even.
        p.setHopCount(p.getPath().size()/2);

        for (int i = 0; i <= p.getPath().size() - 2; i = i + 2) {
            DatapathId src = p.getPath().get(i).getNodeId();
            DatapathId dst = p.getPath().get(i + 1).getNodeId();
            OFPort srcPort = p.getPath().get(i).getPortId();
            OFPort dstPort = p.getPath().get(i + 1).getPortId();
            for (Link l : links.get(p.getPath().get(i))) {
                if (l.getSrc().equals(src) && l.getDst().equals(dst) &&
                        l.getSrcPort().equals(srcPort) && l.getDstPort().equals(dstPort)) {
                    log.debug("Matching link found: {}", l);
                    cost = cost.add(l.getLatency());
                }
            }
        }

        p.setLatency(cost);
        log.debug("Total cost is {}", cost);
        log.debug(p.toString());

    }

    //Yen‘s ksp 求解从src到dst的k条最短路径
    private List<Path> yens(DatapathId src, DatapathId dst, Integer K, Archipelago aSrc, Archipelago aDst) {

        log.debug("YENS ALGORITHM -----------------");
        log.debug("Asking for paths from {} to {}", src, dst);
        log.debug("Asking for {} paths", K);

        // Find link costs
        //找到link的costs
        Map<Link, Integer> linkCost = initLinkCostMap();

        //建立DatapathId 到Set<Link>的映射。即每个sw的link映射
        //portsWithLinks 有link的所有端口，这里的link包含了所有的link
        //links整个拓扑中的所有link，应该是包括了蔟间的link
        //这里想不明白为啥要用所有的link和switches来算。
        Map<DatapathId, Set<Link>> linkDpidMap = buildLinkDpidMap(switches, portsWithLinks, links);

        Map<DatapathId, Set<Link>> copyOfLinkDpidMap = new HashMap<DatapathId, Set<Link>>(linkDpidMap);

        // A is the list of shortest paths. The number in the list at the end should be less than or equal to K
        // B is the list of possible shortest paths found in this function.
        List<Path> A = new ArrayList<Path>();
        //可能的最短路径
        List<Path> B = new ArrayList<Path>();

        // The number of paths requested should never be less than 1.
        if (K < 1) {
            return A;
        }

        /* The switch is not a member of an archipelago. It must not be connected */
        if (aSrc == null || aDst == null) {
            log.warn("One or more switches not connected. Cannot compute path b/t {} and {}", src, dst);
            return A;
        }

        if (!aSrc.equals(aDst)) {
            log.warn("Switches {} and {} not in same archipelago. Cannot compute path", src, dst);
            return A;
        }

        /* Use Dijkstra's to find the shortest path, which will also be the first path in A 
         * 使用Dijkstra's来找到最短路径，它将会作为A中的第一条路径*/
        //这里应该是以dst为root来计算广播树，就是它到其他点的最短路径
        BroadcastTree bt = dijkstra(copyOfLinkDpidMap, dst, linkCost, true);
        /* add this initial tree as our archipelago's broadcast tree (aSrc == aDst) 
         * 将这个最初的树作为我们的群岛的广播树。
         * */
        aSrc.setBroadcastTree(bt);
        /* now add the shortest path */
        log.debug("src {} dst {} tree {}", new Object[] {src, dst, bt});
        //建立从源sw到目的sw的路径，有路径的话，以LinkedList<NodePortTuple>保存路径
        //根据前面的广播树来算
        Path newroute = buildPath(new PathId(src, dst), bt); /* guaranteed to be in same tree */

        if (newroute != null && !newroute.getPath().isEmpty()) { /* should never be null, but might be empty */
        	//这里会计算路径的跳数和整体时延
        	setPathCosts(newroute);
            A.add(newroute);
            log.debug("Found shortest path in Yens {}", newroute);
        }
        else {
            log.debug("No paths found in Yen's!");
            return A;
        }

        // Loop through K - 1 times to get other possible shortest paths
        // 遍历K-1次，来获取其它可能的最短路径
        for (int k = 1; k < K; k++) {
            log.trace("k: {}", k);
            if (log.isTraceEnabled()){
                log.trace("Path Length 'A.get(k-1).getPath().size()-2': {}", A.get(k - 1).getPath().size() - 2);
            }
            //Iterate through i, which is the number of links in the most recent path added to A
            //遍历i, i是添加到A的最近路径中的链接数
            //i<=上一次获得的最短路径的link数量减去2
            for (int i = 0; i <= A.get(k - 1).getPath().size() - 2; i = i + 2) {
                log.trace("i: {}", i);
                //获取上一次获得的最短路径
                List<NodePortTuple> path = A.get(k - 1).getPath();
                //log.debug("A(k-1): {}", A.get(k - 1).getPath());
                // The spur node is the point in the topology where Dijkstra's is called again to find another path
                //刺激节点是拓扑中再次调用Dijkstra寻找另一条路径的点
                DatapathId spurNode = path.get(i).getNodeId();
                // rootPath is the path along the previous shortest path that is before the spur node
                //rootPath是先前的最短路径中，位于刺激节点之前的路径
                Path rootPath = new Path(new PathId(path.get(0).getNodeId(), path.get(i).getNodeId()),
                        path.subList(0, i));


                Map<NodePortTuple, Set<Link>> allLinksCopy = new HashMap<NodePortTuple, Set<Link>>(links);
                // Remove the links after the spur node that are part of other paths in A so that new paths
                // found are unique
                //移除刺激节点之后的属于A中其它路径的部分，由此来构建独一无二的新路径
                for (Path r : A) {
                    if (r.getPath().size() > (i + 1) && r.getPath().subList(0, i).equals(rootPath.getPath())) {
                        allLinksCopy.remove(r.getPath().get(i));//i节点
                        allLinksCopy.remove(r.getPath().get(i+1));//i节点链接的下一个节点
                        /*	
                         * 	i        i+1
                         * SA<------SB       =====>SA    SB
                         * 	\------>/	      
                         * 
                         */
                    }
                }

                // Removes the root path so Dijkstra's doesn't try to go through it to find a path
                //移除root path，这样算法不会尝试通过它来找到一条路径
                Set<DatapathId> switchesCopy = new HashSet<DatapathId>(switches);
                for (NodePortTuple npt : rootPath.getPath()) {
                    if (!npt.getNodeId().equals(spurNode)) {
                        switchesCopy.remove(npt.getNodeId());//移除root path中的sw
                    }
                }

                // Builds the new topology without the parts we want removed
                //新建没有我们移除部分的拓扑
                copyOfLinkDpidMap = buildLinkDpidMap(switchesCopy, portsWithLinks, allLinksCopy);

                // Uses Dijkstra's to try to find a shortest path from the spur node to the destination
                //使用dijkstra，找到从刺激点到目的节点的最短路径
                Path spurPath = buildPath(new PathId(spurNode, dst), dijkstra(copyOfLinkDpidMap, dst, linkCost, true));
                if (spurPath == null || spurPath.getPath().isEmpty()) {
                    log.debug("spurPath is null");
                    continue;
                }

                // Adds the root path and spur path together to get a possible shortest path
                //组合root path以及spur path，来获得一条可能的最短路径
                List<NodePortTuple> totalNpt = new LinkedList<NodePortTuple>();
                totalNpt.addAll(rootPath.getPath());
                totalNpt.addAll(spurPath.getPath());
                Path totalPath = new Path(new PathId(src, dst), totalNpt);
                setPathCosts(totalPath);

                log.trace("Spur Node: {}", spurNode);
                log.trace("Root Path: {}", rootPath);
                log.trace("Spur Path: {}", spurPath);
                log.trace("Total Path: {}", totalPath);
                // Adds the new path into B
                //添加新路径到B
                int flag = 0;
                for (Path r_B : B) {
                    for (Path r_A : A) {
                        if (r_B.getPath().equals(totalPath.getPath()) || r_A.getPath().equals(totalPath.getPath())) {
                            flag = 1;
                        }
                    }
                }
                if (flag == 0) {
                    B.add(totalPath);
                }

                // Restore edges and nodes to graph
            }

            // If we get out of the loop and there isn't a path in B to add to A, all possible paths have been
            // found and return A
            //如果一次循环结束，发现B为空，所有可能的情况已经被找过了，返回A
            if (B.isEmpty()) {
                //log.debug("B list is empty in Yen's");
                break;
            }

            log.debug("Removing shortest path from {}", B);
            // Find the shortest path in B, remove it, and put it in A
            log.debug("--------------BEFORE------------------------");
            for (Path r : B) {
                log.debug(r.toString());
            }
            log.debug("--------------------------------------------");
            Path shortestPath = removeShortestPath(B, linkCost);
            log.debug("--------------AFTER------------------------");
            for (Path r : B) {
                log.debug(r.toString());
            }
            log.debug("--------------------------------------------");
            
            //将可能情况中最短的路径添加到A中，并且清空B
            if (shortestPath != null) {
                log.debug("Adding new shortest path to {} in Yen's", shortestPath);
                A.add(shortestPath);
                log.debug("A: {}", A);
            }
            else {
                log.debug("removeShortestPath returned {}", shortestPath);
            }
        }

        // Set the route counts
        for (Path path : A) {
            path.setPathIndex(A.indexOf(path));
        }
        //log.debug("END OF YEN'S --------------------");
        return A;
    }

    private Path removeShortestPath(List<Path> routes, Map<Link, Integer> linkCost) {
        log.debug("REMOVE SHORTEST PATH -------------");
        // If there is nothing in B, return
        if(routes == null){
            log.debug("Routes == null");
            return null;
        }
        Path shortestPath = null;
        // Set the default shortest path to the max value
        Integer shortestPathCost = Integer.MAX_VALUE;

        // Iterate through B and find the shortest path
        for (Path r : routes) {
            Integer pathCost = 0;
            // Add up the weights of each link in the path
            // TODO Get the path cost from the route object
            for (NodePortTuple npt : r.getPath()) {
                if (links.get(npt) == null || linkCost.get(links.get(npt).iterator().next()) == null) {
                    pathCost++;
                }
                else {
                    pathCost += linkCost.get(links.get(npt).iterator().next());
                }
            }
            log.debug("Path {} with cost {}", r, pathCost);
            // If it is smaller than the current smallest, replace variables with the path just found
            if (pathCost < shortestPathCost) {
                log.debug("New shortest path {} with cost {}", r, pathCost);
                shortestPathCost = pathCost;
                shortestPath = r;
            }
        }

        log.debug("Remove {} from {}", shortestPath, routes);
        // Remove the route from B and return it
        routes.remove(shortestPath);

        log.debug("Shortest path: {}", shortestPath);
        return shortestPath;
    }

    /**
     * Computes end-to-end path including src/dst switch
     * ports in addition to the switches. This chains into
     * {@link #getPath(DatapathId, DatapathId)} below.
     * 计算端到端路径，包括src/dst交换机端口和交换机端口。这个链到{@link #getPath(DatapathId, DatapathId)}下面。
     * @param srcId
     * @param srcPort
     * @param dstId
     * @param dstPort
     * @return
     */
    public Path getPath(DatapathId srcId, OFPort srcPort,
            DatapathId dstId, OFPort dstPort) {
    	//从cache中获取路径，如果路径不存在会返回一个空路径
        Path r = getPath(srcId, dstId);
        
        /* Path cannot be null, but empty b/t 2 diff DPIDs -> not found 
         * 路径不能为空,且源和目的sw不是同一个*/
        if (! srcId.equals(dstId) && r.getPath().isEmpty()) {
            return r;
        }

        /* Else, path is valid (len=0) on the same DPID or (len>0) diff DPIDs */
        //path经过了验证
        List<NodePortTuple> nptList = new ArrayList<NodePortTuple>(r.getPath());
        NodePortTuple npt = new NodePortTuple(srcId, srcPort);
        nptList.add(0, npt); // add src port to the front
        npt = new NodePortTuple(dstId, dstPort);
        nptList.add(npt); // add dst port to the end

        PathId id = new PathId(srcId, dstId);
        r = new Path(id, nptList);
        return r;
    }


    /**
     * Get the fastest path from the pathcache.
     * 从patchcahe中获取最快的路径
     * @param srcId
     * @param dstId
     * @return
     */
    public Path getPath(DatapathId srcId, DatapathId dstId) {
        PathId id = new PathId(srcId, dstId);

        /* Return empty route if srcId equals dstId */
        if (srcId.equals(dstId)) {
            return new Path(id, ImmutableList.of());
        }

        Path result = null;

        try {
            if (!pathcache.get(id).isEmpty()) {
                result = pathcache.get(id).get(0);
            }
        } catch (Exception e) {
            log.warn("Could not find route from {} to {}. If the path exists, wait for the topology to settle, and it will be detected", srcId, dstId);
        }

        if (log.isTraceEnabled()) {
            log.trace("getPath: {} -> {}", id, result);
        }
        return result == null ? new Path(id, ImmutableList.of()) : result;
    }

    //
    //  ITopologyService interface method helpers.
    //

    public boolean isInternalLinkInCluster(DatapathId switchid, OFPort port) {
        return !isAttachmentPointPort(switchid, port);
    }

    public boolean isAttachmentPointPort(DatapathId switchid, OFPort port) {
        NodePortTuple npt = new NodePortTuple(switchid, port);
        if (linksNonBcastNonTunnel.containsKey(npt)) {
            return false;
        }
        return true;
    }

    public DatapathId getClusterId(DatapathId switchId) {
        Cluster c = clusterFromSwitch.get(switchId);
        if (c != null) { 
            return c.getId();
        }
        return switchId;
    }

    public DatapathId getArchipelagoId(DatapathId switchId) {
        Cluster c = clusterFromSwitch.get(switchId);
        if (c != null) {
            return archipelagoFromCluster.get(c).getId();
        }
        return switchId;
    }

    public Set<DatapathId> getSwitchesInCluster(DatapathId switchId) {
        Cluster c = clusterFromSwitch.get(switchId);
        if (c != null) {
            return c.getNodes();
        }
        /* The switch is not known to topology as there are no links connected to it. */
        return ImmutableSet.of(switchId);
    }

    public boolean isInSameCluster(DatapathId switch1, DatapathId switch2) {
        Cluster c1 = clusterFromSwitch.get(switch1);
        Cluster c2 = clusterFromSwitch.get(switch2);
        if (c1 != null && c2 != null) {
            return c1.getId().equals(c2.getId());
        }
        return (switch1.equals(switch2));
    }

    public boolean isNotBlocked(DatapathId sw, OFPort portId) {
        return !isBlockedPort(new NodePortTuple(sw, portId));
    }

    /*
     * Takes finiteBroadcastTree into account to prevent loops in the network
     */
    public boolean isBroadcastAllowedOnSwitchPort(DatapathId sw, OFPort portId) {
        if (!isEdge(sw, portId)){       
            NodePortTuple npt = new NodePortTuple(sw, portId);
            if (portsBroadcastAll.contains(npt))
                return true;
            else return false;
        }
        return true;
    }

    public boolean isConsistent(DatapathId oldSw, OFPort oldPort, DatapathId newSw, OFPort newPort) {
        if (isInternalLinkInCluster(newSw, newPort)) return true;
        return (oldSw.equals(newSw) && oldPort.equals(newPort));
    }

    public boolean isInSameArchipelago(DatapathId s1, DatapathId s2) {
        for (Archipelago a : archipelagos) {
            if (a.getSwitches().contains(s1) && a.getSwitches().contains(s2)) {
                return true;
            }
        }
        return false;
    }

    public Set<DatapathId> getSwitches() {
        return switches;
    }

    public Set<OFPort> getPortsWithLinks(DatapathId sw) {
        return portsWithLinks.get(sw);
    }

    public Set<OFPort> getBroadcastPorts(DatapathId targetSw, DatapathId src, OFPort srcPort) {
        Set<OFPort> result = new HashSet<OFPort>();
        DatapathId clusterId = getClusterId(targetSw);
        for (NodePortTuple npt : clusterPorts.get(clusterId)) {
            if (npt.getNodeId().equals(targetSw)) {
                result.add(npt.getPortId());
            }
        }
        return result;
    }

    public Set<Link> getInternalInterClusterLinks() {
        return linksNonExternalInterCluster;
    }

    public Set<NodePortTuple> getAllBroadcastPorts() {
        return portsBroadcastAll;
    }

    public Set<DatapathId> getClusterIdsInArchipelago(DatapathId sw) {
        Archipelago a = getArchipelago(sw);
        if (a != null) {
            return a.getClusters().stream().map(c -> c.getId()).collect(Collectors.toSet());
        }
        return ImmutableSet.of();
    }

    private void addBroadcastPortToPerSwitchMap(NodePortTuple npt) {
        if (portsBroadcastPerSwitch.containsKey(npt.getNodeId())) {
            portsBroadcastPerSwitch.get(npt.getNodeId()).add(npt.getPortId());
        } else {
            portsBroadcastPerSwitch.put(npt.getNodeId(), 
                    new HashSet<OFPort>(Arrays.asList(npt.getPortId())));
        }
    }

    private void computeBroadcastPortsPerArchipelago() {      
        Set<NodePortTuple> s;
        //添加广播树中的link的端口为广播端口
        for (Archipelago a : archipelagos) {
            s = new HashSet<NodePortTuple>();
            //遍历BoradcastTree的nexthoplinks,HashMap<DatapathId,Link>
            for (Entry<DatapathId, Link> e : a.getBroadcastTree().getLinks().entrySet()) {
                Link l = e.getValue();
                if (l != null) { /* null indicates root node (leads nowhere "up") */
                    NodePortTuple src = new NodePortTuple(l.getSrc(), l.getSrcPort());
                    NodePortTuple dst = new NodePortTuple(l.getDst(), l.getDstPort());
                    
                    /* Accumulate for per-archipelago NPT map 累积每个群岛的NPT*/
                    s.add(src);
                    s.add(dst);

                    /* Add to global NPT set 添加到全局NPT集合
                     * 每个群岛中的所有广播端口
                     * */
                    this.portsBroadcastAll.add(src);
                    this.portsBroadcastAll.add(dst);

                    /* Add to per-switch NPT map 添加到每个switch NPT集合
                     * 添加每个sw的广播端口
                     * */
                    addBroadcastPortToPerSwitchMap(src);
                    addBroadcastPortToPerSwitchMap(dst);
                }
            }

            /* Set accumulated per-this-archipelago NPTs 为每个当前群岛，设置其累积NPTS*/
            //设置每个群岛的广播端口
            portsBroadcastPerArchipelago.put(a.getId(), s); /* guaranteed non-null set per archipelago */
        }

        /* Add ports that hosts connect to 添加hosts连接到的端口*/
        for (DatapathId sw : this.switches) {
            for (OFPort p : this.portsPerSwitch.get(sw)) { /* includes edge and link ports 包含边缘及link端口*/
                NodePortTuple npt = new NodePortTuple(sw, p);
                if (isEdge(sw, p)) {//判断是否是边缘，边缘连接的应该是主机
                    /* Add to per-archipelago NPT map 添加到每个群岛NPT map*/
                	//获取当前遍历到的sw所在的群岛的id
                    DatapathId aId = getArchipelago(sw).getId();
                    if (portsBroadcastPerArchipelago.containsKey(aId)) {
                    	//往对应群岛中添加NPT,广播端口
                        portsBroadcastPerArchipelago.get(aId).add(npt);
                    } else {//考虑全链接hosts的情况
                        s = new HashSet<NodePortTuple>();
                        s.add(npt);
                        portsBroadcastPerArchipelago.put(aId, s);
                    }

                    /* Add to global NPT set 添加到全局*/
                    this.portsBroadcastAll.add(npt);

                    /* Add to per-switch NPT map *添加到每一个交换机*/
                    addBroadcastPortToPerSwitchMap(npt);
                }
            }
        }
    }

    public Set<NodePortTuple> getBroadcastPortsInArchipelago(DatapathId sw) {
        Archipelago a = getArchipelago(sw);
        if (a != null) {
            return portsBroadcastPerArchipelago.get(a.getId());
        }
        return ImmutableSet.of();
    }
    
    public Set<DatapathId> getArchipelagoIds() {
        return archipelagos.stream().map(Archipelago::getId).collect(Collectors.toSet());
    }
} 
