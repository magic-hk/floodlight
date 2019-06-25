package net.floodlightcontroller.routing.algorithms;

import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;

public abstract class KeyHashAlgorithmBase implements IAlgorithm{

	protected String className;//类名
	public abstract String doKeyHashAlgorithm(FlowDemandMetricConstraints metrics);
	
	public String getClassName() {
		return className;
	}

	
}
