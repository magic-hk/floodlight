package net.floodlightcontroller.routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.Masked;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.python.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.types.NodePortTuple;
import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.flowdemand.MetricConstraint;
import net.floodlightcontroller.routing.algorithms.IAlgorithmService;
import net.floodlightcontroller.routing.algorithms.KeyHashAlgorithmBase;
import net.floodlightcontroller.routing.algorithms.RoutingAlgorithmBase;
import net.floodlightcontroller.statistics.IStatisticsService;
import net.floodlightcontroller.topology.ITopologyManagerBackend;
import net.floodlightcontroller.topology.ITopologyService;

/**
 * Separate path-finding and routing functionality from the 
 * topology package. It makes sense to keep much of the core
 * code in the TopologyInstance, but the TopologyManger is
 * too confusing implementing so many interfaces and doing
 * so many tasks. This is a cleaner approach IMHO.
 * 从拓扑包中分离寻路和路由功能。将大部分核心代码保留在TopologyInstance中是有意义的，
 * 但是TopologyManger在实现如此多的接口和执行如此多的任务时过于混乱。这是一个更干净的方法。
 * 
 * All routing and path-finding functionality is visible to
 * the rest of the controller via the IRoutingService implemented
 * by the RoutingManger (this). The RoutingManger performs
 * tasks it can perform locally, such as the handling of
 * IRoutingDecisionChangedListeners, while it defers to the
 * current TopologyInstance (exposed via the ITopologyManagerBackend
 * interface) for tasks best performed by the topology
 * package, such as path-finding.
 * 所有路由和寻路功能通过路由管理器(this)实现的IRoutingService对控制器的其余部分可见。
 * RoutingManger执行它可以在本地执行的任务，例如处理IRoutingDecisionChangedListeners，
 * 而对于最好通过topology 包实现的任务，通过使用当前的TopologyInstance(通过ITopologyManagerBackend开放的接口)来实现。
 * @author rizard
 */
public class RoutingManager implements IFloodlightModule, IRoutingService {
    private Logger log = LoggerFactory.getLogger(RoutingManager.class);
    
    private static ITopologyManagerBackend tm;
    
    private List<IRoutingDecisionChangedListener> decisionChangedListeners;

    private static volatile boolean enableL3RoutingService = false;
    
    /**
     * 添加算法服务支持
     */
    private IAlgorithmService algorithmService;
    
    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleServices() {
        return ImmutableSet.of(IRoutingService.class);
    }

    @Override
    public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
        return ImmutableMap.of(IRoutingService.class, this);
    }

    @Override
    public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
    	 Collection<Class<? extends IFloodlightService>> l =
	                new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ITopologyService.class);
		l.add(IAlgorithmService.class);
        return l;
    }

    /**
     * 添加算法服务支持
     */
    @Override
    public void init(FloodlightModuleContext context) throws FloodlightModuleException {
        log.debug("RoutingManager starting up");
        tm = (ITopologyManagerBackend) context.getServiceImpl(ITopologyService.class);
        algorithmService = context.getServiceImpl(IAlgorithmService.class);
        
        decisionChangedListeners = new ArrayList<IRoutingDecisionChangedListener>();
        Map<String, String> configParameters = context.getConfigParams(this);
        String tmp = configParameters.get("enableL3Routing");
        if (tmp != null) {
        	tmp = tmp.toLowerCase();
        	enableL3RoutingService = tmp.contains("true") ? true : false;
            log.info("Default enableL3RoutingService set to {}.", enableL3RoutingService);
        } else {
            log.info("Default enableL3RoutingService not configured. Using {}.", enableL3RoutingService);
        }
        
      
    }

    @Override
    public void startUp(FloodlightModuleContext context) throws FloodlightModuleException { }

    @Override
    public void setPathMetric(PATH_METRIC metric) {
        tm.setPathMetric(metric);
    }

    @Override
    public PATH_METRIC getPathMetric() {
        return tm.getPathMetric();
    }


    @Override
    public void setMaxPathsToCompute(int max) {
        tm.setMaxPathsToCompute(max);
    }

    @Override
    public int getMaxPathsToCompute() {
        return tm.getMaxPathsToCompute();
    }

    @Override
    public Path getPath(DatapathId src, DatapathId dst) {
        return tm.getCurrentTopologyInstance().getPath(src, dst);
    }

    @Override
    public Path getPath(DatapathId src, OFPort srcPort, DatapathId dst, OFPort dstPort) {
        return tm.getCurrentTopologyInstance().getPath(src, srcPort, dst, dstPort);
    }

    @Override
    public List<Path> getPathsFast(DatapathId src, DatapathId dst) {
        return tm.getCurrentTopologyInstance().getPathsFast(src, dst, tm.getMaxPathsToCompute());
    }

    @Override
    public List<Path> getPathsFast(DatapathId src, DatapathId dst, int numReqPaths) {
        return tm.getCurrentTopologyInstance().getPathsFast(src, dst, numReqPaths);
    }

    @Override
    public List<Path> getPathsSlow(DatapathId src, DatapathId dst, int numReqPaths) {
        return tm.getCurrentTopologyInstance().getPathsSlow(src, dst, numReqPaths);
    }

    /**
     * 多指标参数限制，这里主要是给北向API用的
     * @author hk 
     */
    
    @Override
	public List<Path> getMetricsConstraintPathsSlow(DatapathId src, DatapathId dst, int numReqPaths,
			Map<QOS_METRIC, MetricConstraint> metrics) {
		// TODO Auto-generated method stub
		return tm.getCurrentTopologyInstance().getMerticsConstraintPathsSlow(src,dst,numReqPaths,metrics);
	}

	@Override
	public List<Path> getMetricsConstraintPathsFast(DatapathId src, DatapathId dst, int numReqPaths,
			Map<QOS_METRIC, MetricConstraint> metrics) {
		// TODO Auto-generated method stub
		return tm.getCurrentTopologyInstance().getMerticsConstraintPathsFast(src,dst,numReqPaths,metrics);
	}
    
	
	
	
    
    @Override
    public boolean pathExists(DatapathId src, DatapathId dst) {
        return tm.getCurrentTopologyInstance().pathExists(src, dst);
    }
    
    @Override
    public boolean forceRecompute() {
        return tm.forceRecompute();
    }

    /** 
     * Registers an IRoutingDecisionChangedListener.
     *   
     * @param listener
     * @return 
     */
    @Override
    public void addRoutingDecisionChangedListener(IRoutingDecisionChangedListener listener) {
        decisionChangedListeners.add(listener);
    }
    
    /** 
     * Deletes an IRoutingDecisionChangedListener.
     *   
     * @param listener 
     * @return
     */
    @Override
    public void removeRoutingDecisionChangedListener(IRoutingDecisionChangedListener listener) {
        decisionChangedListeners.remove(listener);
    }

    /** 
     * Listens for the event to the IRoutingDecisionChanged listener and calls routingDecisionChanged().
     *   
     * @param changedDecisions
     * @return
     */
    @Override
    public void handleRoutingDecisionChange(Iterable<Masked<U64>> changedDecisions) {
        for (IRoutingDecisionChangedListener listener : decisionChangedListeners) {
            listener.routingDecisionChanged(changedDecisions);
        }
    }

    @Override
    public void enableL3Routing() {
        enableL3RoutingService = true;
    }

    @Override
    public void disableL3Routing() {
        enableL3RoutingService = false;
    }

    @Override
    public boolean isL3RoutingEnabled() {
        return enableL3RoutingService;
    }

   

	@Override
	public List<Path> getMetricsConstraintPaths(DatapathId src, DatapathId dst, int numReqPaths,
			FlowDemandMetricConstraints metricsContriants, GET_MODE getMode) {
		switch (getMode) {
		case CACHE:
			return getMetricsConstraintPathsFromCache(src,dst,numReqPaths,metricsContriants);
		case ELASTIC_COMPUTE://先尝试从cache中获取数据，如果cache不命中的话，尝试重新计算路径
			//先尝试从缓存中获取数据
			List<Path> targetPaths = getMetricsConstraintPathsFromCache(src,dst,numReqPaths,metricsContriants);
			if(targetPaths.size() > 0) {
				return targetPaths;
			}
			//重新计算路径
			break;
		case FORCE_COMPUTE://强制重新计算路径，这里不从cache中获取数据
			//这里有个算法选择的过程
			break;

		default:
			break;
		}
		return null;
	}
	  /**
     * 从cache中获取缓存
     * @param src
     * @param dst
     * @param numReqPaths
     * @param metrics
     * @return
     */
    private List<Path> getMetricsConstraintPathsFromCache(DatapathId src, DatapathId dst, int numReqPaths,
    		FlowDemandMetricConstraints metricsContriants){
    	return null;
    }

    /**
     * 获取单路径
     */
	@Override
	public Path getMetricsConstraintPath(DatapathId src, OFPort srcPort, DatapathId dst, OFPort dstPort,
			FlowDemandMetricConstraints flowDemandMetricConstraints, GET_MODE getMode) {
		switch (getMode) {
		case CACHE:
			Path cachePath = getMetricsConstraintPathFromCache( src,  srcPort,  dst,  dstPort);
			return cachePath;
		case ELASTIC_COMPUTE://先尝试从cache中获取数据，如果cache不命中的话，尝试重新计算路径
			//先尝试从缓存中获取数据
			Path elasticComptePath = getMetricsConstraintPathFromCache( src,  srcPort,  dst,  dstPort);
			if(elasticComptePath!=null&&elasticComptePath.getPath().size()>0) {
				return elasticComptePath;
			}
			//其它情况,因为这里没有break,会延续执行下一种case中的流程
			
//			//重新计算路径,这里根据指标约束来找到算法.
//			KeyHashAlgorithmBase keyHashAlgorithm = algorithmService.getKeyHashAlgorithm();
//			String keyHash = keyHashAlgorithm.doKeyHashAlgorithm(flowDemandMetricConstraints);
//			RoutingAlgorithmBase routinAlgorithm = algorithmService.getRoutingAlgorithmByKeyHash(keyHash);
//			Path target = routinAlgorithm.doRoutingAlgorithm(src, dst, 1, flowDemandMetricConstraints, tm.getCurrentTopologyInstance()).get(0);
//			return target;
		case FORCE_COMPUTE://强制重新计算路径，这里不从cache中获取数据
			//这里有个算法选择的过程
			KeyHashAlgorithmBase keyHashAlgorithm = algorithmService.getKeyHashAlgorithm();
			String keyHash = keyHashAlgorithm.doKeyHashAlgorithm(flowDemandMetricConstraints);
			RoutingAlgorithmBase routinAlgorithm = algorithmService.getRoutingAlgorithmByKeyHash(keyHash);
			Path target = routinAlgorithm.doRoutingAlgorithm(src, dst, 1, flowDemandMetricConstraints, tm.getCurrentTopologyInstance()).get(0);
			//首尾节点添加端口号
			List<NodePortTuple> nptList = new ArrayList<NodePortTuple>(target.getPath());
	        NodePortTuple npt = new NodePortTuple(src, srcPort);
	        nptList.add(0, npt); // add src port to the front
	        npt = new NodePortTuple(dst, dstPort);
	        nptList.add(npt); // add dst port to the end

	        PathId id = new PathId(src, dst);
	        target = new Path(id, nptList);
			return target;
		default:
			break;
		}
		PathId id = new PathId(src, src);
		return new Path(id, ImmutableList.of());
	}

	//从缓存中获取单路径
	private Path getMetricsConstraintPathFromCache(DatapathId srcSw, OFPort srcPort, DatapathId nodeId, OFPort portId) {
		// TODO Auto-generated method stub
		return null;
	}
	
}