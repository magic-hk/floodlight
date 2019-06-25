package net.floodlightcontroller.routing.algorithms;

import java.util.List;

import org.projectfloodlight.openflow.types.DatapathId;

import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.topology.TopologyInstance;
/**
 * 算法基础类，所有算法类的父类
 * @author hk
 * is-a 
 */
public abstract class RoutingAlgorithmBase implements IAlgorithm{
	
	/**
	 * 这里的类名和serviceMetricConstraints在构造函数中初始化,
	 * switchHash在AlgoritmManager中赋值,这么做的原因在于hash算法可配置化
	 */
	protected String className;//类名
	protected String switchHash;//切换到该类的决策hash
	/**
	 * 这里用指标约束包装器类，而不是用Map原因在于可扩展性
	 * 通过对MetricConstraintsWapper的增量修改可以避免影响原有实现的内容
	 */
	protected FlowDemandMetricConstraints serviceMetricConstraints;
	
	public abstract List<Path> doRoutingAlgorithm(DatapathId src, DatapathId dst, int numReqPaths,
			FlowDemandMetricConstraints metricConstraints,TopologyInstance nowInstance);

	//获取完整类名
	public String getClassName() {
		return className;
	}
	
	//获取算法切换hash值
	public String getSwitchHash() {
		return switchHash;
	}
	public void setSwitchHash(String keyHash) {
		this.switchHash = keyHash;
	}
	
	public FlowDemandMetricConstraints getServiceMetricConstraints(){
		return serviceMetricConstraints;
	}
	
}
