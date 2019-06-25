package net.floodlightcontroller.flowdemand;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.projectfloodlight.openflow.types.U64;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.floodlightcontroller.flowdemand.IFlowDemandService.CONSTRAINT;
import net.floodlightcontroller.flowdemand.IFlowDemandService.METRIC_UNIT;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;

public class MetricConstraint {
	/**
	 * 这里设置的静态变量,便于后面的配置扩展
	 */
	public static Integer defaultPriority = 0;//默认优先级别
	public static Boolean defaultRelax = false;//默认不允许约束松弛，即要求严格约束
	public static Integer defaultRelaxUpRate = 0;//默认上松弛率，这里是默认无松弛，单位比分比
	public static Integer defaultRelaxDownRate = 0;//默认下松弛，这里是默认无松弛，单位百分比
	
	private QOS_METRIC metric;
	private CONSTRAINT constraint;
	private QOS_METRIC childMetric;
	private U64 value;//都用了对应的最小单位 json-value json:"value":500 "unit":"Kb"/"Mb"/"Gb"
	private int priority;//指标约束的优先级别 json: "priority":1
	private boolean relax;//是否严格约束 json: "relax":"true"/"false"
	private int relaxUpRate;
	private int relaxDownRate;
	//private middle box 这里可能还要加middle box
	
	public MetricConstraint(QOS_METRIC metric, CONSTRAINT constraint, U64 value,int priority,boolean relax,int relaxUpRate,int relaxDownRate) {
		super();
		this.metric = metric;
		this.constraint = constraint;
		this.value = value;
		this.priority = priority;
		this.relax = relax;
		this.relaxUpRate = relaxDownRate;
		this.relaxDownRate = relaxDownRate;
	}
	public MetricConstraint(QOS_METRIC metric,QOS_METRIC childMetric, CONSTRAINT constraint, U64 value,int priority,boolean relax,int relaxUpRate,int relaxDownRate) {
		super();
		this.metric = metric;
		this.childMetric = childMetric;
		this.constraint = constraint;
		this.value = value;
		this.priority = priority;
		this.relax = relax;
		this.relaxUpRate = relaxDownRate;
		this.relaxDownRate = relaxDownRate;
	}
	public MetricConstraint() {
		
	}
	//***为切换算法使用的构造函数
	
	/**
	 * 只考虑权重优先级
	 * @param metric
	 * @param priority
	 */
	public MetricConstraint(QOS_METRIC metric,int priority) {
		this.metric = metric;
		this.priority = priority;
	}
	/**
	 * 考虑指标约束和优先级
	 * @param metric
	 * @param constraint
	 * @param priority
	 */
	public MetricConstraint(QOS_METRIC metric,CONSTRAINT constraint,int priority) {
		this.metric = metric;
		this.constraint = constraint;
		this.priority = priority;
	}
	/**
	 * 单指标约束，指定约束
	 * @param metric
	 * @param constraint
	 */
	public MetricConstraint(QOS_METRIC metric,CONSTRAINT constraint) {
		this.metric = metric;
		this.constraint = constraint;
	}
	
	//下面2种先不考虑,感觉比较麻烦
	/**
	 * 单指标约束，指定子指标
	 * @param metric
	 * @param childMetric
	 */
	public MetricConstraint(QOS_METRIC metric,QOS_METRIC childMetric) {
		this.metric = metric;
		this.childMetric = childMetric;
	}
	
	/**
	 * 单指标约束，指定子指标，指定约束
	 * @param metric
	 * @param childMetric 子指标
	 * @param constraint
	 */
//	public MetricConstraint(QOS_METRIC metric,QOS_METRIC childMetric,CONSTRAINT constraint) {
//		this.metric = metric;
//		this.childMetric = childMetric;
//	}
	
	
	/**
	 * @param metric
	 */
	public MetricConstraint(QOS_METRIC metric) {
		this.metric = metric;
	}
	public int getPriority() {
		return priority;
	}
	public void setPriority(int priority) {
		this.priority = priority;
	}
	public QOS_METRIC getMetric() {
		return metric;
	}
	public void setMertic(QOS_METRIC metric) {
		this.metric = metric;
	}
	public CONSTRAINT getConstraint() {
		return constraint;
	}
	public void setConstraint(CONSTRAINT constraint) {
		this.constraint = constraint;
	}
	public U64 getValue() {
		return value;
	}
	public void setValue(U64 value) {
		this.value = value;
	} 
	
	public boolean isRelax() {
		return relax;
	}

	/**
	 * 从json对象中解析
	 * @param mertics
	 * @return
	 * 测试转换json例子：curl -X POST -d  '{"src-dpid":"00:00:00:00:00:00:00:01","dst-dpid":"00:00:00:00:00:00:00:09","num-paths":"3","qos_metrics":[{"qos_metric":"residual_bandwidth","constraint":"more","value":"500", "unit":"Kb","priority":"1", "relax":"true","relaxUpRate":"1","relaxDownRate":"2"},{"qos_metric":"latency","constraint":"less","value":"100", "unit": "ms","relax":"false","relaxUpRate":"1","relaxDownRate":"2"}]}' http://localhost:8080/wm/routing/metrics_constraint/paths/slow/json
	 * 后面可能那个最后http路径会发生变换
	 * 
	 */
	public static Map<QOS_METRIC,MetricConstraint> parseFromJsonArray(JsonArray metrics) throws Exception{
		Map<QOS_METRIC,MetricConstraint> targetMetrics = new HashMap<QOS_METRIC,MetricConstraint>();
		if(metrics == null ) {
			return null;
		}
		Iterator<JsonElement> elemnts = metrics.iterator();
		while(elemnts.hasNext()) {
			JsonObject jo = elemnts.next().getAsJsonObject();
			//获取qos指标类型名称
			QOS_METRIC qosMetric = QOS_METRIC.getQoSMerticByName(jo.get("qos_metric").getAsString());
			if(qosMetric == null) continue;
			//获取约束
			CONSTRAINT constraint = CONSTRAINT.getConstraintByName(jo.get("constraint").getAsString());
			if(constraint == null) continue;

			//获取单位
			long unit = 1;
			//对单位做特殊化处理,如果没有传入unit，则默认使用倍率1
			if(jo.get("unit") != null) {//允许不输入单位，这个时候默认的单位是1
				unit = METRIC_UNIT.getMultipleByName(jo.get("unit").getAsString());
				if(unit == 0){//说明单位没有找到
					throw new Exception("MetricConstraint unit error");
				}
			}
			//获取value值
			long value = 0;
			if(jo.get("value") != null) {//value必须要存在
				value = Long.parseLong(jo.get("value").getAsString());
				//实际value值为，value值乘以单位
				value*= unit;
			}else if(("v").equals(constraint.type)){//约束不为值约束类型,且不存在value值抛出异常
				throw new Exception("MetricConstraint value error");
			}
			
			//约束优先级,这里即使转换错误抛出异常，外部函数也会接收异常.优先级默认为0
			int priority = (jo.get("priority")== null) ? defaultPriority : Integer.parseInt(jo.get("priority").getAsString());
			
			//获取约束情况,判断是否是严格约束，约束默认为true
			boolean relax = (jo.get("relax")!=null)&&!"true".equals(jo.get("relax").getAsString()) ? true : defaultRelax;
			
			int relaxUpRate = 0;			
			int relaxDownRate = 0;
			if(relax) {//如果允许松弛
				relaxUpRate = (jo.get("relaxUpRate") == null) ? defaultRelaxUpRate : Integer.parseInt(jo.get("relaxUpRate").getAsString());
				relaxDownRate = (jo.get("relaxDownRate") == null) ? defaultRelaxDownRate : Integer.parseInt(jo.get("relaxDownRate").getAsString());
			}
			
			MetricConstraint newmc = new  MetricConstraint(qosMetric,constraint,U64.of(value),priority,relax,relaxUpRate,relaxDownRate);
			targetMetrics.put(qosMetric, newmc);
				
		}
		return targetMetrics;
	}
}
