package net.floodlightcontroller.flowdemand;

import java.util.HashMap;
import java.util.Map;

/**
 * flowdemand容器
 * 这里面的具体的数据结构和put\get里面的具体实现可能会发生变化,但是对外接口不变
 */
public class FlowDemandContainer {

	private Map<String,FlowDemandDesc> flowDemands = new HashMap<String,FlowDemandDesc>();
	public void put(String key,FlowDemandDesc value) {
		flowDemands.put(key, value);
	}
	public FlowDemandDesc get(String key) {//这里具体咋样还没有想好咋设计
		return flowDemands.get(key);
	}
}
