package net.floodlightcontroller.flowdemand;

/**
 * 流需求描述，用来记录指定了流指标的流需求
 * 主要包括2个属性:
 * 1.流特征
 * 2.流的指标约束需求
 * @author hk
 *
 */
public class FlowDemandDesc {
	/**
	 * 需求流的特征描述
	 */
	private FlowCharacteristics flowCharacteristics;
	/**
	 * 流需求指标约束
	 */
	private FlowDemandMetricConstraints metricConstraints;
	public FlowCharacteristics getFlowCharacteristics() {
		return flowCharacteristics;
	}
	public void setFlowCharacteristics(FlowCharacteristics flowCharacteristics) {
		this.flowCharacteristics = flowCharacteristics;
	}
	public FlowDemandMetricConstraints getMetricConstraints() {
		return metricConstraints;
	}
	public void setMetricConstraints(FlowDemandMetricConstraints metricConstraints) {
		this.metricConstraints = metricConstraints;
	}
	public FlowDemandDesc(FlowCharacteristics flowCharacteristics, FlowDemandMetricConstraints metricConstraints) {
		super();
		this.flowCharacteristics = flowCharacteristics;
		this.metricConstraints = metricConstraints;
	}
	public FlowDemandDesc() {
		
	}
	
}
