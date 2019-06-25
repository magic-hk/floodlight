package net.floodlightcontroller.flowdemand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.flowdemand.web.FlowDemandWebRoutable;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.RoutingManager;
import net.floodlightcontroller.util.ConfAttributesUtils;

/**
 * 流请求管理器，主要负责处理流请求的存储和映射。
 * @author hk
 *
 */
public class FlowDemandManager implements IFlowDemandService,IFloodlightModule{
	//日志
    private Logger log = LoggerFactory.getLogger(RoutingManager.class);
    
  //defaultDemand={"metric":"SINGLE_METRIC_WEIGHT","childMetric":"LATENCY"}
    
   private  FlowDemandContainer flowDemandContainter;
    
  private FlowDemandMetricConstraints defaultMetricConstraints ;

    
    
    
   /**
    * 需要的服务支持
    */
    protected IRestApiService restApiService;
    
    //提供的服务
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return ImmutableSet.of(IFlowDemandService.class);
	}

	//服务实现
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		 return ImmutableMap.of(IFlowDemandService.class, this);
	}

	//依赖模块
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		// TODO Auto-generated method stub
		 Collection<Class<? extends IFloodlightService>> l =
	                new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IRestApiService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		this.restApiService = context.getServiceImpl(IRestApiService.class);
		this.flowDemandContainter = new FlowDemandContainer();
		Map<String, String> configParameters = context.getConfigParams(this);
		  /**
         * 读取默认指标约束配置
         */
        String jsonAttributes = configParameters.get("metricConstraintDefaultAttributes");
        if (jsonAttributes != null) {
        	try {
            	ConfAttributesUtils.setDefaultAttributesFromJson(MetricConstraint.class, jsonAttributes);
            	//测试是否成功的加载入数据
//            	log.info("---DEFAULT_PRIORITY{}",MetricConstraint.defaultPriority);
//            	log.info("---DEFAULT_RELAX{}",MetricConstraint.defaultRelax);
//            	log.info("---DEFAULT_RELAX_DOWN_RATE{}",MetricConstraint.defaultRelaxDownRate);
//            	log.info("---DEFAULT_RELAX_UP_RATE{}",MetricConstraint.defaultRelaxUpRate);
			} catch (Exception e) {
	            log.info("Default metricConstraintDefaultAttributes not configured. Using {}.", "0 false 0 0");
			}
        } else {
            log.info("Default metricConstraintDefaultAttributes not configured. Using {}.", "0 false 0 0");
        }
        /**
         * 读取默认的流需求
         */
        defaultMetricConstraints = new FlowDemandMetricConstraints(new MetricConstraint(QOS_METRIC.SINGLE_METRIC_WEIGHT,QOS_METRIC.LATENCY));
	}

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		restApiService.addRestletRoutable(new FlowDemandWebRoutable());
		log.info("----flowDemand start----");
	
	}
	
	public String registerFlowDemand() {
		return "";
	}

	@Override
	public String registerFlowDemand(FlowDemandDesc flowDeamndDesc) {
		flowDemandContainter.put(flowDeamndDesc.getFlowCharacteristics().getIdentity(), flowDeamndDesc);
		/**
		 * TODO
		 * 根据流需求中的,预计流到达的时间来判断是否要进行预先的计算.
		 */
		return "注册成功";
	}



	@Override
	public FlowDemandMetricConstraints getFlowDemandMetricConstraintsByEtherPlayload(Ethernet eth) {
		//这里是根据eth荷载来获取flowCharacteristics
        FlowCharacteristics flowCharachteristics = FlowCharacteristics.getInstanceFromEtherPlayload(eth);
        //根据流特征id,从容器中获取流描述
        FlowDemandDesc flowDemandDesc = flowDemandContainter.get(flowCharachteristics.getIdentity());
        if(flowDemandDesc== null || flowDemandDesc.getMetricConstraints() == null) {
        	return defaultMetricConstraints;
        }
       
        return flowDemandDesc.getMetricConstraints();
	}
	
	
}
