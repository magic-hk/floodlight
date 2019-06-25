package net.floodlightcontroller.routing.algorithms.keyhashimpl;

import java.util.Map;

import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
import net.floodlightcontroller.flowdemand.MetricConstraint;
import net.floodlightcontroller.routing.algorithms.KeyHashAlgorithmBase;

public class DefaultKeyHashAlgorithm extends KeyHashAlgorithmBase{

	//单例模式
		private static  DefaultKeyHashAlgorithm instance = null;
		private DefaultKeyHashAlgorithm() {
			super();
			this.className=this.getClass().getCanonicalName();
		}
		public static DefaultKeyHashAlgorithm getInstance() {
			if(instance == null) {
				return new DefaultKeyHashAlgorithm();
			}
			return instance;
		}

	@Override
	public String doKeyHashAlgorithm(FlowDemandMetricConstraints metrics) {
		if(metrics.getHashKey()!=null && !"".equals(metrics.getHashKey())) {//如果当前的包装器中有指定的函数Key,则直接返回
			return metrics.getHashKey();
		}
		// TODO Auto-generated method stub
		return computeMetricsHashCode(metrics.getMetricConstraints());
	}
	//计算Metrics的hash值
	//这里的set值是要排序的，否则相同的QoS metrics可能算出来的hash值不一样
	private  String computeMetricsHashCode(Map<QOS_METRIC,MetricConstraint> metrics) {
			StringBuilder metricNames= new StringBuilder("");
			for (Map.Entry<QOS_METRIC,MetricConstraint> entry : metrics.entrySet()) {
				metricNames.append(entry.getKey().getMetricName());
				if(entry.getValue().getConstraint()!=null) {
					metricNames.append("_").append(entry.getValue().getConstraint().getConstraintName());
				}
				metricNames.append("|");
			}//metric_constraint|
			return String.valueOf(metricNames.toString().hashCode());
	}
	
	
}
