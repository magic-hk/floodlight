package net.floodlightcontroller.flowdemand;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
public class FlowDemandMetricConstraints {
	private Map<QOS_METRIC,MetricConstraint>  metricConstriants;
	
	/*
	 * 这个值可以是直接由北向口来定义，它用来指向算法
	 */
	private String hashKey;

	public Map<QOS_METRIC, MetricConstraint> getMetricConstraints() {
		return metricConstriants;
	}

	
	/**
	 * 对传入的指标集合的metric进行排序，根据约束的优先级来
	 * 将排序的结果放入metric中
	 * @param metrics
	 */	
	public FlowDemandMetricConstraints(Map<QOS_METRIC,MetricConstraint>  metricConstriants) {
		this.metricConstriants = new TreeMap<QOS_METRIC,MetricConstraint>(new ValueComparator(metricConstriants));
		this.metricConstriants.putAll(metricConstriants);
	}
	
	public FlowDemandMetricConstraints(MetricConstraint metricConstriant) {
		Map<QOS_METRIC,MetricConstraint> temp = new HashMap<QOS_METRIC,MetricConstraint>();
		temp.put(metricConstriant.getMetric(), metricConstriant);
		this.metricConstriants = new TreeMap<QOS_METRIC,MetricConstraint>(new ValueComparator(temp));
		this.metricConstriants.put(metricConstriant.getMetric(), metricConstriant);
	}
	
	public void setHashKey(String hashKey) {
		this.hashKey = hashKey;
	}
	public String  getHashKey() {
		return hashKey;
	}
}
class ValueComparator implements Comparator<QOS_METRIC> {  
	  
	Map<QOS_METRIC,MetricConstraint> base;  
    //这里需要将要比较的map集合传进来
    public ValueComparator(Map<QOS_METRIC,MetricConstraint> base) {  
        this.base = base;  
    }  
 
    /**
     * 因为计算hash的时候是对一个集合做hash的,而map原来是乱序的,并且靠用户约束顺序是不可行的
     * 这里实现的排序主要依赖指标优先级\指标默认序
     */
	@Override
	public int compare(QOS_METRIC o1, QOS_METRIC o2) {//默认返回1的在后，负数在前
		MetricConstraint m1 = base.get(o1);
		MetricConstraint m2 = base.get(o2);
		if(m1.getPriority() > m2.getPriority()) {//先比较优先级，优先级大的在前
			return 1;
		}
		if(m1.getPriority() < m2.getPriority()) {
			return -1;
		}
		//优先级别一样比较指标的默认序号,这里其实就可以区分出来了,一个map里面不可能有2个相同的指标key
		if(m1.getMetric().ordinal() > m2.getMetric().ordinal()) {//比较指标默认序号
			return 1;
		}
		return 0;
//		if(m1.getMetric().ordinal() < m2.getMetric().ordinal()) {//比较指标默认序号
//			return -1;
//		}
//		if(m1.getConstraint()!=null && m2.getConstraint()!=null) {//比较约束
//			if(m1.getConstraint().ordinal()>m2.getConstraint().ordinal()) {
//				return 1;
//			}
//			if(m1.getConstraint().ordinal() < m2.getConstraint().ordinal()) {
//				return -1;
//			}
//		}
//		if(m1.getConstraint()!=null) {
//			return 1;
//		}else {
//			return -1;
//		}
	}  
}  