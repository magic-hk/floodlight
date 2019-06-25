package net.floodlightcontroller.routing.algorithms.routingimpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.projectfloodlight.openflow.types.DatapathId;
import org.python.google.common.collect.ImmutableList;

import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
import net.floodlightcontroller.flowdemand.MetricConstraint;
import net.floodlightcontroller.routing.Path;
import net.floodlightcontroller.routing.PathId;
import net.floodlightcontroller.routing.algorithms.RoutingAlgorithmBase;
import net.floodlightcontroller.topology.TopologyInstance;

/**
 * 单指标约束算法，模板类
 * 这个类用来实现原有的,单指标算法
 * 
 * @author hk
 * 
 */
@SuppressWarnings("all")
public class DefaultSingleMetricAlgorithm extends RoutingAlgorithmBase {
	
	//是否注册,根据该属性，在加载的时候，可以选择性的注册
	private static final boolean WANT_TO_REGISTER = true;
	
	//单例模式
	private static  DefaultSingleMetricAlgorithm instance = null;
	
	//实例构造，在这里面添加算法的类名，和支持的指标
	private DefaultSingleMetricAlgorithm() {
		super();
		this.className=this.getClass().getCanonicalName();
		/**
		 * 单一指标约束，求权重最小
		 * 第一个参数必定是QOS_METRIC.SINGLE_METRIC_WEIGHT，指定该算法支持的是单一指标
		 * 在做算法key hash的时候不关心实际运行的时候用的是哪一个子约束指标比如：HOPCOUNT\LATENCY\RESIDUAL_BANDWIDTH
		 * 可以有第2个参数也可以没有
		 * 做hash只考虑了指标类型和约束类型2个参数
		 * 
		 * 这里我甚至可以直接设置key hash,北向接口的需求可以指明要用哪一种算法.
		 */
		MetricConstraint mcFirst = new MetricConstraint(QOS_METRIC.SINGLE_METRIC_WEIGHT);
		//MetricConstraint mcFirst = new MetricConstraint(QOS_METRIC.SINGLE_METRIC_WEIGHT,CONSTRAINT.MIN);
		Map<QOS_METRIC,MetricConstraint> metricConstraints = new HashMap<QOS_METRIC,MetricConstraint>();
		metricConstraints.put(QOS_METRIC.SINGLE_METRIC_WEIGHT, mcFirst);
		this.serviceMetricConstraints = new FlowDemandMetricConstraints(metricConstraints);
		//this.switchHash="DefaultSingleMetricAlgorithm";设置了switchHash,就不会再通过serviceMetricConstraints来计算hash了
		
	}
	//单例对外接口名称同一全部都是getInstance
	public static DefaultSingleMetricAlgorithm getInstance() {
		if(instance == null) {
			return new DefaultSingleMetricAlgorithm();
		}
		return instance;
	}
	/**
	 * 对于单一指标
	 * metrics中的map的唯一MetricConstraint对象的值：
	 * metric= QOS_METRIC.SINGLE_METRIC_WEIGHT//指标类型，单一指标权重
	 * childMetric= QOS_METRIC.LATENCY || QOS_METRIC.RESIDUAL_BANDWIDTH ......//子指标，指定了具体是哪个单一指标
	 * constraint = QOS_METRIC.MAX || QOS_METRIC.MIN || ...//具体的约束,可以没有,由算法本身自己来处理
	 */
	@Override
	public List<Path> doRoutingAlgorithm(DatapathId src, DatapathId dst, int numReqPaths,
			FlowDemandMetricConstraints metrics, TopologyInstance nowInstance) {
		// TODO Auto-generated method stub
		//AlgorithmManager.statisticsService.collectStatistics(true);;
		
		//测试代码
		List<Path> testList = new ArrayList<Path>();
		PathId id = new PathId(src, src);
		Path path = new Path(id, ImmutableList.of());
		testList.add(path);
		return testList;
	}

	
	
	

	

}
