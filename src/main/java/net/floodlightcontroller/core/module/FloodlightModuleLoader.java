/**
 *    Copyright 2013, Big Switch Networks, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.core.module;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;

import net.floodlightcontroller.core.module.FloodlightModulePriority.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

/**
 * Finds all Floodlight modules in the class path and loads/starts them.
 * @author alexreimers
 *
 */
public class FloodlightModuleLoader {
    protected static final Logger logger =
            LoggerFactory.getLogger(FloodlightModuleLoader.class);

    private Map<Class<? extends IFloodlightService>,
                  Collection<IFloodlightModule>> serviceMap;
    private Map<IFloodlightModule,
                  Collection<Class<? extends
                                   IFloodlightService>>> moduleServiceMap;
    private Map<String, IFloodlightModule> moduleNameMap;
    private List<IFloodlightModule> loadedModuleList;

    private final FloodlightModuleContext floodlightModuleContext;

    protected boolean startupModules;

    private static URI configFile;

    public static final String COMPILED_CONF_FILE =
            "floodlightdefault.properties";
    public static final String FLOODLIGHT_MODULES_KEY =
            "floodlight.modules";

    public FloodlightModuleLoader() {
        loadedModuleList = Collections.emptyList();
        floodlightModuleContext = new FloodlightModuleContext(this);
        startupModules = true;
    }

    /**
     * Gets the map of modules and their names.
     * @return An UNMODIFIABLE map of the modules and their names.
     */
    public synchronized Map<String, IFloodlightModule> getModuleNameMap() {
        if(moduleNameMap == null)
            return ImmutableMap.of();
        else
            return Collections.unmodifiableMap(moduleNameMap);
    }

    /**
     * Gets the list of modules in the order that they will be/were initialized
     * @return An UNMODIFIABLE list of loaded modules, or null if
     * not initialized.
     */
    public List<IFloodlightModule> getModuleList() {
        if (loadedModuleList == null)
            return Collections.emptyList();
        else
            return Collections.unmodifiableList(loadedModuleList);
    }

    /**
     * Return the location of the config file that was used to initialize
     * floodlight. If no config file was specified (i.e. floodlight was
     * configured from the default resource), then the return value is null.
     * @return location of the config file or null if no config file was
     * specified
     */
    public static URI getConfigFileURI() {
        return configFile;
    }

    /**
     * Finds all IFloodlightModule(s) in the classpath. It creates 3 Maps.
     * serviceMap -> Maps a service to a module
     * 在classpth上找到所有的IFloodlightModlue.它创建了3个map，
     * 
     * serviceMap -> 映射一个服务到一个模块
     * moduleServiceMap -> Maps a module to all the services it provides
     * moduleServiceMap -> 映射一个模块到所有的服它提供的服务
     * 
     * moduleNameMap -> Maps the string name to the module
     * modlueNameMap -> 映射string name 到module
     * 
     * @throws FloodlightModuleException If two modules are specified in the
     * configuration that provide the same service.
     */
    protected synchronized void findAllModules(Collection<String> mList)
            throws FloodlightModuleException {
        if (serviceMap != null)
            return;
        //映射一个服务到一个moudlue，<server,mods>
        serviceMap = new HashMap<>();
        //映射一个模块到所有它提供的服务。<mod,servs>
        moduleServiceMap = new HashMap<>();
        //<module.class.name,mod>
        moduleNameMap = new HashMap<>();

        /**
         *  这里要为服务借口找到其的实现子类，来填充map
         *  java类加载机制，决定了其从子类找父类容易，子类加载前必须要先加载父类
         *  而从父类找实现类比较困难
         */
        
        // Get all the current modules in the classpath
        //获得当前线程的类加载器，获取当前classpath下的所有当前module
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
      
        //参数：表示server的interface或抽象类,
       //用于加载提供程序配置文件和提供程序类的类装入器，如果系统类装入器为空，则为空
        //moduleLoader是所有实现了IFloodlightModule的类的一个加载器，
        ServiceLoader<IFloodlightModule> moduleLoader =
                ServiceLoader.load(IFloodlightModule.class, cl);
        // Iterate for each module, iterate through and add it's services
        //为每个module进行迭代，迭代，并且添加它的服务
        Iterator<IFloodlightModule> moduleIter = moduleLoader.iterator();
        //遍历所有的模块类
        while (moduleIter.hasNext()) {
            IFloodlightModule m = null;
            try {
                m = moduleIter.next();
            } catch (ServiceConfigurationError sce) {
                logger.error("Could not find module: {}", sce.getMessage());
                continue;
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Found module " + m.getClass().getName());
            }

            // Set up moduleNameMap
            //本地类或者匿名类或者数组类型的时候,getCanonicalName()=null,其它情况下和getName相同
            moduleNameMap.put(m.getClass().getCanonicalName(), m);

            // Set up serviceMap
            //设置serviceMap
            Collection<Class<? extends IFloodlightService>> servs =
                    m.getModuleServices();//由子类的覆写实现，这个方法里面添加了子类实现的服务父接口
            if (servs != null) {
            	//添加mod到其实现的服务接口list的映射
                moduleServiceMap.put(m, servs);
                //遍历服务接口
                for (Class<? extends IFloodlightService> s : servs) {
                    Collection<IFloodlightModule> mods =
                            serviceMap.get(s);
                    if (mods == null) {//表面还没有给服务接口，添加对应的实现子类
                        mods = new ArrayList<IFloodlightModule>();
                        serviceMap.put(s, mods);
                    }
                    mods.add(m);
                    // Make sure they haven't specified duplicate modules in
                    // the config
                    //确保它们没有在配置中指定重复的模块
                    int dupInConf = 0;
                    for (IFloodlightModule cMod : mods) {
                        if (mList.contains(cMod.getClass().getCanonicalName()))
                            dupInConf += 1;
                    }

                    if (dupInConf > 1) {//多余1，表明配置有错误
                        StringBuilder sb = new StringBuilder();
                        for (IFloodlightModule mod : mods) {
                            sb.append(mod.getClass().getCanonicalName());
                            sb.append(", ");
                        }
                        String duplicateMods = sb.toString();
                        String mess = "ERROR! The configuration file " +
                                "specifies more than one module that " +
                                "provides the service " +
                                s.getCanonicalName() +
                                ". Please specify only ONE of the " +
                                "following modules in the config file: " +
                                duplicateMods;
                        throw new FloodlightModuleException(mess);
                    }
                }
            }
        }
    }

    /**
     * Loads the modules from a specified configuration file.
     * 从指定的配置文件中加载模块，floodlightdefault.properties
     * @param fName The configuration file path
     * @return An IFloodlightModuleContext with all the modules to be started
     * @throws FloodlightModuleException
     */
    public IFloodlightModuleContext loadModulesFromConfig(String fName)
            throws FloodlightModuleException {
        Properties prop = new Properties();
        Collection<String> configMods = new ArrayList<>();

        if (fName == null) {
            logger.info("Loading default modules");
            InputStream is = this.getClass().getClassLoader().
                                    getResourceAsStream(COMPILED_CONF_FILE);
            mergeProperties(is, null, configMods, prop);
        } else {
        	//读取配置文件
            File confFile = new File(fName);
            if (! confFile.exists())
                throw new FloodlightModuleConfigFileNotFoundException(fName);
            logger.info("Loading modules from {}", confFile.getPath());
            if (confFile.isFile()) {
            	//从配置文件中读取模块到configMods,并且将其它属性赋值给prop
                mergeProperties(null, confFile,
                                configMods, prop);
            } else {
                File[] files = confFile.listFiles();
                Arrays.sort(files);
                for (File f : files) {
                    logger.debug("Loading conf.d file {}", f.getPath());

                    if (f.isFile() &&
                        f.getName().matches(".*\\.properties$")) {
                        mergeProperties(null, f, configMods, prop);
                    }
                }
            }
        }
        return loadModulesFromList(configMods, prop);
    }

    private void mergeProperties(InputStream is,
                                 File confFile,
                                 Collection<String> configMods,
                                 Properties prop)
                                         throws FloodlightModuleException {
        try {
            Properties fprop = new Properties();
            if (is != null) {
                fprop.load(is);
            } else {
                try (FileInputStream fis = new FileInputStream(confFile)) {
                    fprop.load(fis);
                }
            }
            //FLOODLIGHT_MODULES_KEY=floodlight.modules
            String moduleList = fprop.getProperty(FLOODLIGHT_MODULES_KEY);
            if (moduleList != null) {
            	//替换换行，所有的模块根据‘，’,拆分出来，结果放入到configMods集合里面
                moduleList = moduleList.replaceAll("\\s", "");
                configMods.addAll(Arrays.asList(moduleList.split(",")));
            }
            fprop.remove(FLOODLIGHT_MODULES_KEY);

            prop.putAll(fprop);
        } catch (IOException e) {
            throw new FloodlightModuleException(e);
        }
    }

    /**
     * Loads modules (and their dependencies) specified in the list
     * 加载在list中指定的模型，以及它们依赖。
     * @param configMods The fully-qualified module names
     * @return The ModuleContext containing all the loaded modules
     * @throws FloodlightModuleException
     */
    public synchronized IFloodlightModuleContext
            loadModulesFromList(Collection<String> configMods,
                                Properties prop)
                                        throws FloodlightModuleException {
        logger.debug("Starting module loader");
        
        //从配置文件里面读取出来的，模块的具体类明，包括了所在的包
        findAllModules(configMods);

        ArrayList<IFloodlightModule> moduleList = new ArrayList<>();
        Map<Class<? extends IFloodlightService>, IFloodlightModule> moduleMap =
                new HashMap<>();
        HashSet<String> modsVisited = new HashSet<>();

        ArrayDeque<String> modsToLoad = new ArrayDeque<>(configMods);
        while (!modsToLoad.isEmpty()) {//遍历所有的模块，添加依赖模块
            String moduleName = modsToLoad.removeFirst();
            traverseDeps(moduleName, //模块名称
            		     modsToLoad,//所有模块集合
                         moduleList, //开始为空的arraylist
                         moduleMap, //开始为空的moduleMap<IFloodlightService,IFloodlightModule>
                         modsVisited//已经访问的模块集合
                         );
        }

        parseConfigParameters(prop);

        loadedModuleList = moduleList;
        //初始化
        initModules(moduleList);
        if(startupModules)
        	//启动
            startupModules(moduleList);

        return floodlightModuleContext;
    }

    private void traverseDeps(String moduleName,
                              Collection<String> modsToLoad,
                              ArrayList<IFloodlightModule> moduleList,
                              Map<Class<? extends IFloodlightService>,
                                  IFloodlightModule> moduleMap,
                              Set<String> modsVisited)
                                      throws FloodlightModuleException {
        if (modsVisited.contains(moduleName)) return;
        modsVisited.add(moduleName);
        //获取对应模块名称的模块对象
        IFloodlightModule module = moduleNameMap.get(moduleName);
        if (module == null) {
            throw new FloodlightModuleException("Module " +
                    moduleName + " not found");
        }

        // Add its dependencies to the stack 将其依赖项添加到堆栈中
        Collection<Class<? extends IFloodlightService>> deps =
                module.getModuleDependencies();//获取模块的依赖服务
        if (deps != null) {
            for (Class<? extends IFloodlightService> c : deps) {//遍历依赖服务
                IFloodlightModule m = moduleMap.get(c);
                if (m == null) {//依赖模块还没有添加
                	//获取依赖服务的实现模块
                    Collection<IFloodlightModule> mods = serviceMap.get(c);
                    // Make sure only one module is loaded确保只有一个模块被加载
                    if ((mods == null) || (mods.size() == 0)) {//模块不存在的情况
                        throw new FloodlightModuleException("ERROR! Could not " +
                                "find an IFloodlightModule that provides service " +
                                c.toString());
                    } else if (mods.size() == 1) {//模块大小为1
                        IFloodlightModule mod = mods.iterator().next();
                        traverseDeps(mod.getClass().getCanonicalName(),
                                     modsToLoad, moduleList,
                                     moduleMap, modsVisited);
                    } else {
                        boolean found = false;
                        for (IFloodlightModule moduleDep : mods) {//对于每个实现服务的模块
                            String d = moduleDep.getClass().getCanonicalName();
                            if (modsToLoad.contains(d)) {
                                modsToLoad.remove(d);
                                traverseDeps(d,
                                             modsToLoad, moduleList,
                                             moduleMap, modsVisited);
                                found = true;
                                break;
                            }
                        }
                        if (!found) {
                            Priority maxp = Priority.MINIMUM;
                            ArrayList<IFloodlightModule> curMax = new ArrayList<>();
                            for (IFloodlightModule moduleDep : mods) {
                                FloodlightModulePriority fmp =
                                        moduleDep.getClass().
                                        getAnnotation(FloodlightModulePriority.class);
                                Priority curp = Priority.NORMAL;
                                if (fmp != null) {
                                    curp = fmp.value();
                                }
                                if (curp.value() > maxp.value()) {
                                    curMax.clear();
                                    curMax.add(moduleDep);
                                    maxp = curp;
                                } else if  (curp.value() == maxp.value()) {
                                    curMax.add(moduleDep);
                                }
                            }

                            if (curMax.size() == 1) {
                                traverseDeps(curMax.get(0).
                                             getClass().getCanonicalName(),
                                             modsToLoad, moduleList,
                                             moduleMap, modsVisited);
                            } else {
                                StringBuilder sb = new StringBuilder();
                                for (IFloodlightModule mod : curMax) {
                                    sb.append(mod.getClass().getCanonicalName());
                                    sb.append(", ");
                                }
                                String duplicateMods = sb.toString();

                                throw new FloodlightModuleException("ERROR! Found more " +
                                        "than one (" + mods.size() + ") IFloodlightModules that provides " +
                                        "service " + c.toString() +
                                        ". This service is required for " + moduleName +
                                        ". Please specify one of the following modules in the config: " +
                                        duplicateMods);
                            }
                        }
                    }
                }
            }
        }

        // Add the module to be loaded
        addModule(moduleMap, moduleList, module);
    }

    /**
     * Add a module to the set of modules to load and register its services
     * 向一组模块中添加一个模块以加载和注册其服务
     * @param moduleMap the module map
     * @param moduleList the module set
     * @param module the module to add
     */
    protected void addModule(Map<Class<? extends IFloodlightService>,
                                           IFloodlightModule> moduleMap,
                            Collection<IFloodlightModule> moduleList,
                            IFloodlightModule module) {
        Collection<Class<? extends IFloodlightService>> servs =
                moduleServiceMap.get(module);
        if (servs != null) {
            for (Class<? extends IFloodlightService> c : servs)
                moduleMap.put(c, module);
        }
        moduleList.add(module);
    }

    /**
     * Allocate  service implementations and then init all the modules
     * @param moduleSet The set of modules to call their init function on
     * @throws FloodlightModuleException If a module can not properly be loaded
     */
    protected void initModules(Collection<IFloodlightModule> moduleSet)
                                           throws FloodlightModuleException {
        for (IFloodlightModule module : moduleSet) {
            // Get the module's service instance(s)
            Map<Class<? extends IFloodlightService>,
                IFloodlightService> simpls = module.getServiceImpls();

            // add its services to the context
            if (simpls != null) {
                for (Entry<Class<? extends IFloodlightService>,
                        IFloodlightService> s : simpls.entrySet()) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Setting " + s.getValue() +
                                     "  as provider for " +
                                     s.getKey().getCanonicalName());
                    }
                    if (floodlightModuleContext.getServiceImpl(s.getKey()) == null) {
                        floodlightModuleContext.addService(s.getKey(),
                                                           s.getValue());
                    } else {
                        throw new FloodlightModuleException("Cannot set "
                                                            + s.getValue()
                                                            + " as the provider for "
                                                            + s.getKey().getCanonicalName()
                                                            + " because "
                                                            + floodlightModuleContext.getServiceImpl(s.getKey())
                                                            + " already provides it");
                    }
                }
            }
        }

        for (IFloodlightModule module : moduleSet) {
            // init the module
            if (logger.isDebugEnabled()) {
                logger.debug("Initializing " +
                             module.getClass().getCanonicalName());
            }
            module.init(floodlightModuleContext);
        }
    }

    /**
     * Call each loaded module's startup method
     * @param moduleSet the module set to start up
     * @throws FloodlightModuleException
     */
    protected void startupModules(Collection<IFloodlightModule> moduleSet)
            throws FloodlightModuleException {
        for (IFloodlightModule m : moduleSet) {
            if (logger.isDebugEnabled()) {
                logger.debug("Starting " + m.getClass().getCanonicalName());
            }
            m.startUp(floodlightModuleContext);
        }
    }

    /** Tuple of floodlight module and run method */
    private static class RunMethod {
        private final IFloodlightModule module;
        private final Method method;
        public RunMethod(IFloodlightModule module, Method method) {
            this.module = module;
            this.method = method;
        }

        public void run() throws FloodlightModuleException {
            try {
                if (logger.isDebugEnabled()) {
                    logger.debug("Running {}", this);
                }
                method.invoke(module);
            } catch (IllegalAccessException | IllegalArgumentException
                    | InvocationTargetException e) {
                throw new FloodlightModuleException("Failed to invoke "
                        + "module Run method " + this, e);
            }
        }

        @Override
        public String toString() {
            return module.getClass().getCanonicalName() + "." + method;
        }


    }

    public void runModules() throws FloodlightModuleException {
        List<RunMethod> mainLoopMethods = Lists.newArrayList();
        //遍历所有模块
        for (IFloodlightModule m : getModuleList()) {
        	//反射获得模块类中的所有方法
            for (Method method : m.getClass().getDeclaredMethods()) {
            	//方法如果存在这样的注释，则返回指定类型的元素的注释，这里的注释类型是Run
            	//全局搜索，发现只有FloodlightProvider的run函数上面有这个注释，而这个方法里面是调用control的运行
                Run runAnnotation = method.getAnnotation(Run.class);
                if (runAnnotation != null) {
                    RunMethod runMethod = new RunMethod(m, method);
                    if(runAnnotation.mainLoop()) {//@Run(mainLoop=true)
                        mainLoopMethods.add(runMethod);
                    } else {
                        runMethod.run();
                    }
                }
            }
        }
        if(mainLoopMethods.size() == 1) {//只能有一个主循环方法
            mainLoopMethods.get(0).run();
        } else if (mainLoopMethods.size() > 1) {
            throw new FloodlightModuleException("Invalid module configuration -- "
                    + "multiple run methods annotated with mainLoop detected: " + mainLoopMethods);
        }
    }

    /**
     * Parses configuration parameters for each module
     * @param prop The properties file to use
     */
    protected void parseConfigParameters(Properties prop) {
        if (prop == null) return;

        Enumeration<?> e = prop.propertyNames();
        while (e.hasMoreElements()) {
            String key = (String) e.nextElement();

            String configValue = null;
            int lastPeriod = key.lastIndexOf(".");
            String moduleName = key.substring(0, lastPeriod);
            String configKey = key.substring(lastPeriod + 1);
            // Check to see if it's overridden on the command line
            String systemKey = System.getProperty(key);
            if (systemKey != null) {
                configValue = systemKey;
            } else {
                configValue = prop.getProperty(key);
            }

            IFloodlightModule mod = moduleNameMap.get(moduleName);
            if (mod == null) {
                logger.debug("Module {} not found or loaded. " +
                            "Not adding configuration option {} = {}",
                            new Object[]{moduleName, configKey, configValue});
            } else {
            	logger.debug("Adding configuration option {} = {} for module {}",
                        new Object[]{configKey, configValue, moduleName});
                floodlightModuleContext.addConfigParam(mod, configKey, configValue);
            }
        }
    }

    public boolean isStartupModules() {
        return startupModules;
    }

    public void setStartupModules(boolean startupModules) {
        this.startupModules = startupModules;
    }
}
