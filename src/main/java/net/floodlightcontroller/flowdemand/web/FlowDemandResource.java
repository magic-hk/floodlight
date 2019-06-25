package net.floodlightcontroller.flowdemand.web;

import java.util.Map;

import org.restlet.resource.Post;
import org.restlet.resource.ServerResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.floodlightcontroller.core.types.JsonObjectWrapper;
import net.floodlightcontroller.flowdemand.FlowCharacteristics;
import net.floodlightcontroller.flowdemand.FlowDemandDesc;
import net.floodlightcontroller.flowdemand.FlowDemandMetricConstraints;
import net.floodlightcontroller.flowdemand.IFlowDemandService;
import net.floodlightcontroller.flowdemand.IFlowDemandService.QOS_METRIC;
import net.floodlightcontroller.flowdemand.MetricConstraint;

//接收应用过来的流请求，这里主要是进行了json转换对象，校验及调用FlowDemandManager中的处理函数

@SuppressWarnings("all")
public class FlowDemandResource extends ServerResource{
	 protected static Logger log = LoggerFactory.getLogger(FlowDemandResource.class);
	 
	//curl -X POST -d  '{"tes":"test"}' http://localhost:8080/wm/flowdemand/register/json
	@Post
    public Object retrieveMetrics(String fmJson) {
        IFlowDemandService flowdemand =
                (IFlowDemandService)getContext().getAttributes().
                       get(IFlowDemandService.class.getCanonicalName());
        JsonObject json = new JsonParser().parse(fmJson).getAsJsonObject();
        if(json!=null) {
        	JsonObject jsonCharact = json.get("flowCharacteristics") !=null ? json.get("flowCharacteristics").getAsJsonObject() : null;
            JsonArray jsonMetricConstraints = json.get("metricConstraints") !=null ? json.get("metricConstraints").getAsJsonArray() : null;
            if(jsonCharact!=null) {
            	try {//这里写的还是比较粗糙的
            		//从北向接口获取数据
        		    FlowCharacteristics flowCharachteristics = FlowCharacteristics.parseFromJsonObject(jsonCharact);
                	Map<QOS_METRIC, MetricConstraint> metricConstraints = MetricConstraint.parseFromJsonArray(jsonMetricConstraints);
                	//包装
                	FlowDemandMetricConstraints flowDemandMetricConstraints = new FlowDemandMetricConstraints(metricConstraints);
                	//包装
                	FlowDemandDesc flowDemandDesc = new FlowDemandDesc(flowCharachteristics,flowDemandMetricConstraints);
                	//注册流需求
                	String result = flowdemand.registerFlowDemand(flowDemandDesc);
                	return JsonObjectWrapper.of(result);
                } catch (Exception e) {
        			// TODO Auto-generated catch block
        			e.printStackTrace();
        			return JsonObjectWrapper.of("注册失败,流特征或指标约束集合格式错误!!!");
        		}
            }
            return JsonObjectWrapper.of("注册失败,流特征信息不存在!!!");
        }      
        return JsonObjectWrapper.of("注册失败,流特征及流指标约束获取失败!!!");
    }
	
}
