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
 * 最大带宽，最小时延
 * @author hk
 *
 */
@SuppressWarnings("all")
public class MaxBandwidthMinDelayConstraint extends RoutingAlgorithmBase {
	
	//是否注册,根据该属性，在加载的时候，可以选择性的注册
	private static final boolean WANT_TO_REGISTER = true;
	
	//单例模式
	private static  MaxBandwidthMinDelayConstraint instance = null;
	
	//实例构造，在这里面添加算法的类名，和支持的指标
	private MaxBandwidthMinDelayConstraint() {
		super();
		this.className=this.getClass().getCanonicalName();
		/**
		 * 注册该算法服务指标，并可以根据情况给予优先级
		 * 这里可能会出现覆盖的情况
		 */
		//时延最小，优先级0
		MetricConstraint mcFirst = new MetricConstraint(QOS_METRIC.LATENCY,CONSTRAINT.PATH_MIN,0);
		//带宽最大，优先级1
		MetricConstraint mcSecond = new MetricConstraint(QOS_METRIC.RESIDUAL_BANDWIDTH,CONSTRAINT.PATH_MAX,1);	
		
		Map<QOS_METRIC,MetricConstraint> metricConstraints = new HashMap<QOS_METRIC,MetricConstraint>();
		metricConstraints.put(QOS_METRIC.LATENCY, mcFirst);
		metricConstraints.put(QOS_METRIC.RESIDUAL_BANDWIDTH, mcSecond);
		//这里会计算hash
		this.serviceMetricConstraints = new FlowDemandMetricConstraints(metricConstraints);
	}
	//单例对外接口名称同一全部都是getInstance
	public static MaxBandwidthMinDelayConstraint getInstance() {
		if(instance == null) {
			return new MaxBandwidthMinDelayConstraint();
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
