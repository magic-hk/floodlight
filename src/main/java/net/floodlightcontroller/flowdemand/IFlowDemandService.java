package net.floodlightcontroller.flowdemand;

import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.packet.Ethernet;

public interface IFlowDemandService extends IFloodlightService{
	 
	   
	   enum QOS_METRIC {
		   //加性指标 additive
	       LATENCY("latency","A"), 
	       HOPCOUNT("hopcount","A"), 
	       JITTER("jitter","A"),
	       HOPCOUNT_AVOID_TUNNELS("hopcount_avoid_tunnels","A"), 
	       SINGLE_METRIC_WEIGHT("single_metric_weight","A"),//单一指标权重约束
	       
		   //乘性指标 multiplicative
	       LOSS("loss","M"),
	       RELIABILITY("reliability","M"),
	       
	       //凹性指标 concave
		   RESIDUAL_BANDWIDTH("residual_bandwidth","C");//剩余带宽
		  
		   
	       String name;
	       String type;
	       
	       private QOS_METRIC(String ns,String ts) {
	           name = ns;
	           type = ts;
	       }
	       public String getMetricName() {
	           return name;
	       }
	       public String getMetricType() {
	           return type;
	       }
	       public static QOS_METRIC getQoSMerticByName(String name){
	           for(QOS_METRIC metric:QOS_METRIC.values()){
	               if(metric.getMetricName().equals(name)){
	                   return metric;
	               }
	           }
	           return  null;
	       }

	   }
	   /**
	    * 约束类型
	    */
	   enum CONSTRAINT {	 
		   //值类型属性
	       PATH_LESS("path_less","V"), 
	       LINK_LESS("link_less","V"),
	       PATH_LESS_EQUAL("path_less_equal","V"), 
	       LINK_LESS_EQUAL("link_less_equal","V"), 
	       PATH_EQUAL("path_equal","V"), 
	       LINK_EQUAL("link_equal","V"), 
	       PATH_MORE_EQUAL("path_more_equal","V"), 
	       LINK_MORE_EQUAL("link_more_equal","V"), 
	       PATH_MORE("path_more","V"),
	       LINK_MORE("link_more","V"),
		   DIFFERENT("different","V"),
		   PATH_MAX("path_max","EV"),//extremum value极值约束
		   PATH_MIN("path_min","EV");
		   String name;
	       String type;
	       private CONSTRAINT(String name,String type) {
	           this.name = name;
	           this.type = type;
	       }    
	       
	       public String getConstraintName() {
	           return name;
	       }
	       public String getType() {
	    	   return type;
	       }
	       public static CONSTRAINT getConstraintByName(String name){
	           for(CONSTRAINT constraint:CONSTRAINT.values()){
	               if(constraint.getConstraintName().equals(name)){
	                   return constraint;
	               }
	           }
	           return  null;
	       }
	   }
	   
	   /**
	    * 指标单位
	    * @author hk
	    *
	    */
	   enum METRIC_UNIT{
		   //speed 单位,基础单位比特率每秒
		   Bit("b",1), 
	       Kbit("Kb",1000),
	       Mbit("Mb",1000000),
	       Gbit("Gb",1000000000),
	       
	       //时间单位
	       MS("ms",1),
	       S("s",1000),
		   M("m",60000),
		   H("h",3600000);
		   String name;
		   long  multiple;
		   
		   private METRIC_UNIT(String s,long times) {
	           name = s;
	           multiple = times;
	       }    
		   public String getUnitName() {
	           return name;
	       }
		   public long getMutiple() {
			   return multiple;
		   }
		   public static long getMultipleByName(String name){
	           for(METRIC_UNIT unit:METRIC_UNIT.values()){
	               if(unit.getUnitName().equals(name)){
	                   return unit.getMutiple();
	               }
	           }
	           return  0;
	       }
	       
	   }
	   /**
	    * 北向口注册流需求
	    * @param flowDemandDesc
	    * @return
	    */
	   public String registerFlowDemand(FlowDemandDesc flowDemandDesc);
	   
	   
	   /**
	    * 根据网络荷载找到合适的流需求指标约束集合
	    * @param eth
	    * @return
	    */
	   public FlowDemandMetricConstraints getFlowDemandMetricConstraintsByEtherPlayload(Ethernet eth);
	   
	   
}