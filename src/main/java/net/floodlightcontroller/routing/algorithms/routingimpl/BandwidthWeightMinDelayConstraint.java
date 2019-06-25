package net.floodlightcontroller.routing.algorithms.routingimpl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.types.DatapathId;

import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;
import net.floodlightcontroller.flowdemand.IFlowDemandService.CONSTRAINT;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
import net.floodlightcontroller.flowdemand.MetricConstraint;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.routing.algorithms.RoutingAlgorithmBase;
import net.floodlightcontroller.topology.TopologyInstance;

/**
 * 基于带宽权重的，时延最小算法
 * @author hk
 *
 */
@SuppressWarnings("all")
public class BandwidthWeightMinDelayConstraint extends RoutingAlgorithmBase {
	
	//是否注册,根据该属性，在加载的时候，可以选择性的注册
	private static final boolean WANT_TO_REGISTER = true;
	
	//单例模式
	private static  BandwidthWeightMinDelayConstraint instance = null;
	
	//实例构造，在这里面添加算法的类名，和支持的指标
	private BandwidthWeightMinDelayConstraint() {
		super();
		this.className=this.getClass().getCanonicalName();
		/**
		 * 注册该算法服务指标，并可以根据情况给予优先级
		 * 这里可能会出现覆盖的情况
		 * 这里通过QOS_METRIC优先级\默认序排序来统一指标约束Map的顺序
		 * 并通过指标\约束来实现hash计算,具体的hash实现算法依赖于配置的算法
		 */
		MetricConstraint mcFirst = new MetricConstraint(QOS_METRIC.LATENCY,CONSTRAINT.PATH_MIN,0);
		MetricConstraint mcSecond = new MetricConstraint(QOS_METRIC.RESIDUAL_BANDWIDTH,CONSTRAINT.PATH_MIN,1);
		Map<QOS_METRIC,MetricConstraint> metricConstraints = new HashMap<QOS_METRIC,MetricConstraint>();
		metricConstraints.put(QOS_METRIC.LATENCY, mcFirst);
		metricConstraints.put(QOS_METRIC.RESIDUAL_BANDWIDTH, mcSecond);
		this.serviceMetricConstraints = new FlowDemandMetricConstraints(metricConstraints);
	}
	//单例对外接口名称同一全部都是getInstance
	public static BandwidthWeightMinDelayConstraint getInstance() {
		if(instance == null) {
			return new BandwidthWeightMinDelayConstraint();
		}
		return instance;
	}

	
	
	@Override
	public List<Path> doRoutingAlgorithm(DatapathId src, DatapathId dst, int numReqPaths,
			FlowDemandMetricConstraints metrics, TopologyInstance nowInstance) {
		// TODO Auto-generated method stub
		return null;
	}

	
	
	

	

}
