package net.floodlightcontroller.routing.algorithms;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.algorithms.keyhashimpl.DefaultKeyHashAlgorithm;
import net.floodlightcontroller.routing.algorithms.routingimpl.DefaultSingleMetricAlgorithm;
import net.floodlightcontroller.statistics.IStatisticsService;

/**
 * 算法容器类，用来存储算法实例及默认属性
 * 
 * @author hk
 */
@SuppressWarnings("all")
public class AlgorithmManager implements IFloodlightModule,IAlgorithmService{
	
	private Logger log = LoggerFactory.getLogger(AlgorithmManager.class);
	
	
	/**
	 * 路由算法相关属性
	 */
	
	//默认包名
	private static String defaultRoutingAlgorithmsPacketName = "net.floodlightcontroller.routing.algorithms.routingimpl";
	
	//路由算法所在的包名称集合
	private final static List<String> ROUTING_ALGORITHMS_PACKET_NAMES= new ArrayList<String>();

	//默认的路由算法接口名称，这里只有一个,不可配置化	
	private static final Class<RoutingAlgorithmBase> ROUTING_ALGORITHMS_INTERFACE = RoutingAlgorithmBase.class;
	
	//public static String[] ALGORITHMS_INTERFACE_NAMES= {};

	
	//默认路由算法
	private static RoutingAlgorithmBase defaultRoutingAlgorithm=DefaultSingleMetricAlgorithm.getInstance();
	
	//<switchHash，算法实现类>,路由算法存储器
	private final static Map<String,RoutingAlgorithmBase> ROUTING_ALGORITHMS = new HashMap<String,RoutingAlgorithmBase>();
	
	
	/**
	 * key hash相关属性
	 */
	//默认的keyHashAlgorithm
	private static KeyHashAlgorithmBase keyHashAlgorithm = DefaultKeyHashAlgorithm.getInstance();
    
	//切换算法接口名称，这里只有一个
	private static final Class<KeyHashAlgorithmBase>  KEY_HASH_ALGORITHM_INTERFACE= KeyHashAlgorithmBase.class;        
	
	//切换算法类名
	private  static String switchKeyHashAlgorithmClassName = "net.floodlightcontroller.routing.algorithms.keyhashimpl.DefaultKeyHashAlgorithm";
	
	
	
	//北向接口服务
	protected IRestApiService restApiService;

	//统计服务接口,这里只能设置成public,因为protected是只能本类\同包\不同包子类访问
	public static IStatisticsService statisticsService;
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// TODO Auto-generated method stub
		return ImmutableSet.of(IAlgorithmService.class);
	}


	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		// TODO Auto-generated method stub
		 return ImmutableMap.of(IAlgorithmService.class, this);
	}


	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		 Collection<Class<? extends IFloodlightService>> l =
	                new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IRestApiService.class);
		l.add(IStatisticsService.class);
		return l;
	}


	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		this.restApiService = context.getServiceImpl(IRestApiService.class);
		this.statisticsService = context.getServiceImpl(IStatisticsService.class);
		 Map<String, String> configParameters = context.getConfigParams(this);
		/**
		 * 从配置文件读取默认路由算法包名(可以多个)、默认路由算法类名(只有1个)、默认路由算法的服务指标(可以是多个)、key hash算法类名
		 * 这里的配置可以有,可以无,没有配置的时候全部采用默认的数据值
		 */
		
		/**
		 * 读取配置文件中设置的keyHash算法
		 */
		String tmpKeyHashAlgorithm = configParameters.get("switchKeyHashAlgorithmClassName");
		if(tmpKeyHashAlgorithm != null) {
			try {
				Class tempClass = Class.forName(tmpKeyHashAlgorithm);
				if( tempClass != null && KEY_HASH_ALGORITHM_INTERFACE.isAssignableFrom(tempClass)) {
					switchKeyHashAlgorithmClassName = tmpKeyHashAlgorithm;
				}
			} catch (Exception e) {
				log.info("！！！Configure KeyHashAlgorithm ERROR！！！");
				e.printStackTrace();
			}
		}
		//注册KeyHash算法
		try {
			registerKeyHashAlgorithm();
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		//log.info(keyHashAlgorithm.getClassName());
		

		/**
		 * 扫秒默认路由算法包，实现路由算法的注册
		 */
		String routingAlgorithmsPacketNames = configParameters.get("routingAlgorithmsPacketNames");
		if(routingAlgorithmsPacketNames != null) {//注册路由算法包名不为空才处理
			String[] names = routingAlgorithmsPacketNames.split(";");
			for(String name:names) {
				String packagePath = name.replace(".", "/");
				String t=Thread.currentThread().getContextClassLoader().getResource("").getPath(); 
				File dir = new File(t+"/"+packagePath);
			    if (!dir.exists() || !dir.isDirectory()) {
			      continue;
			    }
			    ROUTING_ALGORITHMS_PACKET_NAMES.add(name);//只会添加存在的包
			}
		}
		//log.info("-----ROUTING_ALGORITHMS_PACKET_NAMES{}",ROUTING_ALGORITHMS_PACKET_NAMES);
		try {
			registerRoutingAlgorithms();
		} catch (Exception e) {
			log.info("！！！算法注册失败！！！");
			e.printStackTrace();
		}
		
		/**
		 * 这里只负责默认的路由算法配置,而不负责,默认的需求指标
		 * 当算法不命中的时候,会利用一个默认流需求,比如:
		 * defaultDemand={"metric":"SINGLE_METRIC_WEIGHT","childMetric":"LATENCY"}
		 * 其在FlowDemand中配置
		 * 
		 * 当前设值的默认路由算法DefaultSingleMetricAlgorithm服务的指标约束(即计算hash时候用的)
		 * :"metric":"SINGLE_METRIC_WEIGHT"
		 * 内部可以根据childMetric,来选择是哪个单指标
		 * 
		 */
		String defaultRoutingAlgorithmName = configParameters.get("defaultRoutingAlgorithm");
		if(defaultRoutingAlgorithmName!=null&&!defaultRoutingAlgorithm.getClassName().equals(defaultRoutingAlgorithmName)) {
			Class clz = loadClass(defaultRoutingAlgorithmName);
			if(clz!=null&&ROUTING_ALGORITHMS_INTERFACE.isAssignableFrom(clz)) {
				try {
					Method getInstance = clz.getMethod("getInstance");
					RoutingAlgorithmBase algorithm = (RoutingAlgorithmBase) getInstance.invoke(clz);
					defaultRoutingAlgorithm = algorithm;
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		//默认路由算法设置keyHash值
		String keyHash = defaultRoutingAlgorithm.getSwitchHash();
		if(keyHash == null ||"".equals(keyHash)) {//只有在keyHash不存在的情况下,才会使用计算的keyhash.
			String tempKeyHash = keyHashAlgorithm.doKeyHashAlgorithm(defaultRoutingAlgorithm.getServiceMetricConstraints());
			defaultRoutingAlgorithm.setSwitchHash(tempKeyHash);
		}
		
	}


	

	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		// TODO Auto-generated method stub
		log.info("--------AlgorithmManager Start--------");
	}
	
	/**
	 * 注册keyHasAlgorithm
	 * @throws Exception
	 */
	public static void registerKeyHashAlgorithm() throws Exception{
		if(!switchKeyHashAlgorithmClassName.equals(keyHashAlgorithm.getClassName())) {//如果配置的依旧是默认算法，则不处理
			Class clz = loadClass(switchKeyHashAlgorithmClassName);
			if(clz!=null) {
				//获取算法实例接口
				Method getInstance = clz.getMethod("getInstance");
				//创建实例
				keyHashAlgorithm=(KeyHashAlgorithmBase) getInstance.invoke(clz);
			}
		}
	}
	
	/**
	 * 扫描一个包下的，所有实现或继承接口的类
	 *
	 * @return
	 */
	private  void registerRoutingAlgorithms() throws Exception{
		if(ROUTING_ALGORITHMS_PACKET_NAMES.size() == 0) {//如果算法包名集合大小为0，添加默认的包名
			ROUTING_ALGORITHMS_PACKET_NAMES.add(defaultRoutingAlgorithmsPacketName);
		}
		for(String packageName:ROUTING_ALGORITHMS_PACKET_NAMES) {//扫描所有的路由算法包
			registerRoutingAlgorithmsByScanPackage(packageName);
		}
	}
	
	
	
	
	/**
	 * 这里扫描处理的是.class文件
	 * @param packagePath
	 */
	private  void registerRoutingAlgorithmsByScanPackage(String packageName) {
		String packagePath = packageName.replace(".", "/");
		//获取绝对路径,这里存在一个问题，我达包成jar包以后这样可不可以不太清楚
		String t=Thread.currentThread().getContextClassLoader().getResource("").getPath(); 
		File dir = new File(t+"/"+packagePath);
	    if (!dir.exists() || !dir.isDirectory()) {
	       return;
	    }
	    File[] dirFiles = dir.listFiles();
	    if(dirFiles == null || dirFiles.length == 0) {
	    	return;
	    }
	    String className;
	    Class clz;
	    for(File f :dirFiles) {
	    	if(f.isDirectory()) {//net.floodlightcontroller.routing.algorithms.test
	    		registerRoutingAlgorithmsByScanPackage(packagePath+"/"+f.getName());
	    		continue;
	    	}
	    	className = f.getName();
	    	className = className.substring(0, className.length() - 6);
	    	clz = loadClass(packageName + "." + className);
	    	if(clz != null && ROUTING_ALGORITHMS_INTERFACE.isAssignableFrom(clz)) {//成功加载类
	    		try {
	    			//先判断该算法是否要注册
	    			Field register = clz.getDeclaredField("WANT_TO_REGISTER");
	    			register.setAccessible(true);
	    			if((boolean) register.get(clz)){
	    				//获取单例对外接口，没有参数
						Method getInstance = clz.getMethod("getInstance");
						//获取算法实例
						RoutingAlgorithmBase algorithm = (RoutingAlgorithmBase) getInstance.invoke(clz);
						String keyHash = algorithm.getSwitchHash();
						if(keyHash == null ||"".equals(keyHash)) {//只有在keyHash不存在的情况下,才会使用计算的keyhash.
							String tempKeyHash = keyHashAlgorithm.doKeyHashAlgorithm(algorithm.getServiceMetricConstraints());
							algorithm.setSwitchHash(tempKeyHash);
							keyHash=tempKeyHash;
						}
						//完成注册
						ROUTING_ALGORITHMS.put(keyHash, algorithm);
	    			}
				} catch (Exception e) {
					log.info("---exception{}",e);
					e.printStackTrace();
				} 
	    	}
	    }
	}
	/**
	 * 加载类
	 * @param fullClzName
	 * @return
	 */
	private static Class<?> loadClass(String fullClzName){
	    try {
	        return Thread.currentThread().getContextClassLoader().loadClass(fullClzName);
	    } catch (ClassNotFoundException e) {
	        
	    }
	    return null;
	}

	//对外接口
	/**
	 * 获取默认路由算法实例
	 * @return
	 */
	@Override
	public RoutingAlgorithmBase getDefaultRoutingAlgorithm() {
		return defaultRoutingAlgorithm;
	}
	/**
	 * 获取所有的路由算法
	 * @return
	 */
	@Override
	public Map<String, RoutingAlgorithmBase> getAllRoutingAlgorithms(){
		return ROUTING_ALGORITHMS;
	}
	//--------------
	//从json解析数据
	//--------------


	@Override
	public KeyHashAlgorithmBase getKeyHashAlgorithm() {
		// TODO Auto-generated method stub
		return keyHashAlgorithm;
	}


	@Override
	public RoutingAlgorithmBase getRoutingAlgorithmByKeyHash(String keyHash) {
		//查看测试
//		RoutingAlgorithmBase look =defaultRoutingAlgorithm;
//		ROUTING_ALGORITHMS.get(keyHash);//测试点
		return ROUTING_ALGORITHMS.get(keyHash) == null ? defaultRoutingAlgorithm:ROUTING_ALGORITHMS.get(keyHash);
		
	}

	

}
