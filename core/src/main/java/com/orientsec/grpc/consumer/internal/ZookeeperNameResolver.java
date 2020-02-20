/*
 * Copyright 2019 Orient Securities Co., Ltd.
 * Copyright 2019 BoCloud Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.orientsec.grpc.consumer.internal;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.orientsec.grpc.common.constant.GlobalConstants;
import com.orientsec.grpc.common.constant.RegistryConstants;
import com.orientsec.grpc.common.resource.RegisterCenterConf;
import com.orientsec.grpc.common.resource.SystemConfig;
import com.orientsec.grpc.common.util.ConfigFileHelper;
import com.orientsec.grpc.common.util.IpUtils;
import com.orientsec.grpc.common.util.LoadBalanceUtil;
import com.orientsec.grpc.common.util.MapUtils;
import com.orientsec.grpc.common.util.StringUtils;
import com.orientsec.grpc.consumer.FailoverUtils;
import com.orientsec.grpc.consumer.check.CheckDeprecatedService;
import com.orientsec.grpc.consumer.core.ConsumerServiceRegistry;
import com.orientsec.grpc.consumer.model.ServiceProvider;
import com.orientsec.grpc.consumer.routers.Router;
import com.orientsec.grpc.registry.common.URL;
import com.orientsec.grpc.registry.common.utils.UrlUtils;
import com.orientsec.grpc.registry.service.Consumer;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.ManagedChannel;
import io.grpc.NameResolver;
import io.grpc.Status;
import io.grpc.internal.SharedResourceHolder;
import io.grpc.internal.SharedResourceHolder.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.orientsec.grpc.common.constant.GlobalConstants.LB_STRATEGY;

/**
 * 基于zookeeper的 {@link NameResolver}.
 *
 * @author dengjq
 * @since 2018-3-31
 */
public class ZookeeperNameResolver extends NameResolver {
  private static final Logger logger = LoggerFactory.getLogger(ZookeeperNameResolver.class);
  /** 确定zkURL的定时任务时间间隔 */
  private static final long FIND_DELAY = 8;

  private final String authority;
  private String serviceName;
  private final Resource<ScheduledExecutorService> timerServiceResource;
  private final Resource<Executor> executorResource;
  //@GuardedBy("this")
  private boolean shutdown;
  //@GuardedBy("this")
  private ScheduledExecutorService timerService;
  //@GuardedBy("this")
  private Executor executor;
  //@GuardedBy("this")
  private boolean resolving;
  //@GuardedBy("this")
  private volatile Listener listener;

  private ConsumerServiceRegistry registry;
  private Object lock = new Object();

  private ProvidersListener providersListener = new ProvidersListener();
  private RoutersListener routersListener = new RoutersListener();
  private ConfiguratorsListener configuratorsListener = new ConfiguratorsListener();

  // 当前服务接口的所有提供者列表(未经过路由规则过滤)
  private Map<String, ServiceProvider> allProviders = new ConcurrentHashMap<String, ServiceProvider>();

  private Map<String, ServiceProvider> serviceProviderMap = new ConcurrentHashMap<String, ServiceProvider>();
  private Map<String, ServiceProvider> providersForLoadBalance = new ConcurrentHashMap<String, ServiceProvider>();
  private volatile int providersForLoadBalanceFlag = 0;
  private volatile int providersCountAfterLoadBalance = Integer.MAX_VALUE;// 经过负载均衡算法之后的服务提供者个数

  private volatile Map<String, LB_STRATEGY> loadBlanceStrategyMap = null;

  private volatile List<Router> routes = new ArrayList<>();

  private volatile URL zkRegistryURL;
  private volatile String subscribeId;//订阅id
  private volatile URL consumerUrl;
  private volatile String serviceVersion, group;
  private volatile String consumerIP;// 当前客户端的IP
  private volatile ManagedChannel mc;

  private volatile boolean useInitProvidersData;

  private volatile ScheduledExecutorService findZkExecutor = Executors.newScheduledThreadPool(1);;
  private volatile ScheduledFuture<?> findZkFuture;
  private final Runnable findZkTask = new Runnable() {
    @Override
    public void run() {
      findZkUrlForTwoRc();
    }
  };
  private final String cannotFindZkMsg;


  ZookeeperNameResolver(URI targetUri, String name, Attributes params,
                        Resource<ScheduledExecutorService> timerServiceResource,
                        Resource<Executor> executorResource) {
    this.timerServiceResource = timerServiceResource;
    this.executorResource = executorResource;
    // Must prepend a "//" to the name when constructing a URI, otherwise it will be treated as an
    // opaque URI, thus the authority and host of the resulted URI would be null.
    URI nameUri = URI.create("//" + name);
    authority = Preconditions.checkNotNull(nameUri.getAuthority(),
            "nameUri (%s) doesn't have an authority", nameUri);
    this.serviceName = Preconditions.checkNotNull(name, "host");

    this.useInitProvidersData = false;

    cannotFindZkMsg = "配置文件里同时配置了公共、私有注册注册中心，但是在两个注册中心都找不到服务["
            + serviceName + "]的注册信息，[" + FIND_DELAY + "]秒后再次查找服务端所在的注册中心！";
  }

  public Map<String, ServiceProvider> getServiceProviderMap() {
    return serviceProviderMap;
  }

  public void setServiceProviderMap(Map<String, ServiceProvider> serviceProviderMap) {
    this.serviceProviderMap = serviceProviderMap;
  }

  public void setRegistry(ConsumerServiceRegistry registry) {
    this.registry = registry;
  }

  @Override
  public final String getServiceAuthority() {
    return authority;
  }

  public NameResolver build() {
    URL zkUrl;
    String key = RegisterCenterConf.getConsumerRcProKey();
    boolean hasTwoRc = GlobalConstants.PUBLIC_PRIVATE_REGISTRY_CENTER.equals(key);

    if (hasTwoRc) {
      zkUrl = findProviderZk();
      if (zkUrl == null) {
        logger.warn(cannotFindZkMsg);
        findZkFuture = findZkExecutor.schedule(findZkTask, FIND_DELAY, TimeUnit.SECONDS);

        // 暂时将客户端注册到公共注册中心
        zkUrl = UrlUtils.getRegisterURL(key);
      }
    } else {
      zkUrl = UrlUtils.getRegisterURL(key);
    }

    if (registry != null && zkUrl != null) {
      zkRegistryURL = zkUrl;
      registry = registry.forTarget(zkUrl).build();
    }

    return this;
  }

  /**
   * 客户端配置文件里同时配置了公共、私有注册注册中心时，通过查询服务端注册信息在哪个注册中心上，确定当前客户端所使用的注册中心
   *
   * @author sxp
   * @since 2019/10/8
   */
  private URL findProviderZk() {
    URL zkUrl;

    Map<String, String> parameters = new HashMap<>();
    parameters.put(GlobalConstants.Consumer.Key.INTERFACE, serviceName);
    parameters.put(GlobalConstants.CommonKey.CATEGORY, RegistryConstants.PROVIDERS_CATEGORY);
    URL queryUrl = new URL(RegistryConstants.GRPC_PROTOCOL, "0.0.0.0", 0, parameters);

    URL publicZkUrl = UrlUtils.getRegisterURL(GlobalConstants.REGISTRY_CENTTER_ADDRESS);
    Consumer consumer = new Consumer(publicZkUrl);

    List<URL> urls = consumer.lookup(queryUrl);
    if (urls != null && !urls.isEmpty()) {
      zkUrl = publicZkUrl;
    } else {
      URL privateZkUrl = UrlUtils.getRegisterURL(GlobalConstants.PRIVATE_REGISTRY_CENTER_ADDRESS);
      consumer = new Consumer(privateZkUrl);
      urls = consumer.lookup(queryUrl);
      if (urls != null && !urls.isEmpty()) {
        zkUrl = privateZkUrl;
      } else {
        return null;
      }
    }

    return zkUrl;
  }

  /**
   * 查找服务端所在的注册中心的实现方法(定时任务调用)
   *
   * @author sxp
   * @since 2019/10/8
   */
  private void findZkUrlForTwoRc() {
    URL zkUrl = findProviderZk();

    if (zkUrl == null) {
      logger.warn(cannotFindZkMsg);
      findZkFuture = findZkExecutor.schedule(findZkTask, FIND_DELAY, TimeUnit.SECONDS);
    } else {
      if (!zkUrl.equals(zkRegistryURL)) {
        // 注销客户端注册信息
        unRegistry();

        // 更新客户端注册中心
        zkRegistryURL = zkUrl;
        registry = registry.forTarget(zkUrl).build();

        // 客户端重新注册
        registry();

        // 需要将缓冲中的服务列列表通知给this.listener
        resolveServerInfoWithLock();
      }
    }
  }

  public void registry() {
    if (registry != null) {
      computeLoadBlanceStrategyMap();

      consumerIP = IpUtils.getIP4WithPriority();// 客户端的IP地址
      initServiceVersion();// 初始化客户端指定的服务的版本--必须放在注册之前
      initGroup();//初始化客户端的GROUP,分组信息

      providersListener.init(this, this.registry);
      routersListener.init(this, this.registry);
      configuratorsListener.init(this, this.registry);

      Map<String, Object> params = new ConcurrentHashMap<String, Object>();
      params.put(GlobalConstants.Consumer.Key.INTERFACE, serviceName);
      subscribeId = registry.register(params, providersListener, routersListener, configuratorsListener);
      consumerUrl = URL.valueOf(subscribeId);
      useInitProvidersData = true;
    }
  }

  /**
   * 初始化客户端group的值
   */
  private void initGroup(){
    Properties properties = SystemConfig.getProperties();
    //判断配置文件
    if (properties == null) {
      group = "";
      return;
    }

    //判断配置文件中是否有KEY
    String key = ConfigFileHelper.CONSUMER_KEY_PREFIX + GlobalConstants.CommonKey.GROUP;
    if (!properties.containsKey(key)) {
      group = "";
      return;
    }

    //把配置文件中KEY的值,赋值
    group = properties.getProperty(key);
    if (group == null) {
      group = "";
    }
  }


  /**
   * 初始化客户端指定的服务的版本
   */
  private void initServiceVersion() {
    Properties properties = SystemConfig.getProperties();
    if (properties == null) {
      serviceVersion = "";
      return;
    }

    String key = ConfigFileHelper.CONSUMER_KEY_PREFIX + GlobalConstants.Consumer.Key.SERVICE_VERSION;
    if (!properties.containsKey(key)) {
      serviceVersion = "";
      return;
    }

    serviceVersion = properties.getProperty(key);
    if (serviceVersion == null) {
      serviceVersion = "";
    }

    if (StringUtils.isEmpty(serviceVersion)) {
      return;
    }

    // 如果当前应用需要调用多个服务，属性值按照冒号逗号的方式分隔，
    // 例如com.orientsec.examples.Greeter:1.0.0,com.orientsec.examples.Hello:1.2.1
    if (!serviceVersion.contains(":")) {
      return;
    }

    if (!serviceVersion.contains(serviceName)) {
      serviceVersion = "";
      return;
    }

    String[] array = serviceVersion.split(",");
    String[] arrayOfInner;
    String service, version;

    serviceVersion = "";

    for (String serviceAndVersion : array) {
      if (StringUtils.isEmpty(serviceAndVersion)) {
        continue;
      }

      serviceAndVersion = serviceAndVersion.trim();
      if (!serviceAndVersion.contains(":")) {
        continue;
      }

      arrayOfInner = serviceAndVersion.split(":");
      if (arrayOfInner.length != 2) {
        continue;
      }

      service = arrayOfInner[0];
      if (service != null) {
        service = service.trim();
      }
      if (serviceName.equals(service)) {
        version = arrayOfInner[1];
        if (version != null) {
          version = version.trim();
        }
        serviceVersion = version;
        break;
      }
    }

    if (serviceVersion == null) {
      serviceVersion = "";
    }
  }


  public void unRegistry() {
    if (registry != null && subscribeId != null && subscribeId.length() > 0) {
      // 删除与当前客户端相关的数据(服务调用出错次数、时间、当前客户端对应的服务提供者列表)
      FailoverUtils.removeDateByConsumerId(subscribeId);

      // 将客户端从注册中心注销
      registry.unSubscribe(subscribeId);
    }
  }

  @Override
  public final synchronized void start(Listener listener) {
    Preconditions.checkState(this.listener == null, "already started");
    timerService = SharedResourceHolder.get(timerServiceResource);
    executor = SharedResourceHolder.get(executorResource);
    this.listener = Preconditions.checkNotNull(listener, "listener");
    resolve();
  }

  @Override
  public final synchronized void refresh() {
    // 不让grpc主动调用选择服务器的方法
    // Preconditions.checkState(listener != null, "not started");
    // resolve();
  }

  @Override
  public final void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  /**
   * 黑白名单、负载均衡等过滤、选择算法过在此处调用
   */
  private void applyFilter() {
    applyRoute();
    generateProvidersForLB();
    loadBalancer(null);
  }

  /**
   * 应用route规则，即黑白名单
   */
  private void applyRoute() {
    Map<String, ServiceProvider> serviceProviders = serviceProviderMap;
    if (consumerUrl != null) {
      for (Router route : routes) {
        serviceProviders = route.route(serviceProviders, consumerUrl);
      }
    }
    serviceProviderMap = serviceProviders;
  }

  /**
   * 给定的host:port是否在被过滤的服务器列表中（即是否在黑名单中）
   *
   * @author sxp
   * @since 2019/3/1
   */
  @Override
  public boolean isInfilteredProviders(String host, int port) {
    Map<String, ServiceProvider> serviceProviders = new HashMap<String, ServiceProvider>();

    ServiceProvider provider = new ServiceProvider();
    provider.setHost(host);
    provider.setPort(port);

    Map<String, String> parameters = new HashMap<>(MapUtils.capacity(5));
    parameters.put(RegistryConstants.CATEGORY_KEY, RegistryConstants.PROVIDERS_CATEGORY);
    parameters.put(GlobalConstants.CommonKey.SIDE, RegistryConstants.PROVIDER_SIDE);
    parameters.put(GlobalConstants.Provider.Key.INTERFACE, serviceName);
    parameters.put(GlobalConstants.Provider.Key.APPLICATION, getConfig(GlobalConstants.COMMON_APPLICATION));
    parameters.put(GlobalConstants.CommonKey.PROJECT, getConfig(GlobalConstants.COMMON_PROJECT));

    URL url = new URL(RegistryConstants.GRPC_PROTOCOL, host, port, parameters);
    provider.setUrl(url);

    serviceProviders.put(host + ":" + port, provider);

    for (Router route : routes) {
      serviceProviders = route.route(serviceProviders, consumerUrl);
    }

    if (serviceProviders == null || serviceProviders.size() == 0) {
      return true;
    }

    return false;
  }

  private String getConfig(String key) {
    Properties pros = SystemConfig.getProperties();
    if (pros == null) {
      return "";
    }

    String value = null;

    if (pros.containsKey(key)) {
      value = pros.getProperty(key);
      if (value != null) {
        value = value.trim();
      }
    }

    if (value == null) {
      value = "";
    }

    return value;
  }

  /**
   * 服务端分组信息是否满足客户端
   *
   * @author sxp
   * @since 2019/11/14
   */
  @Override
  public boolean isGroupValid(String host, int port) {
    String consumerGroup = this.group;
    consumerGroup = StringUtils.trim(consumerGroup);
    if (StringUtils.isEmpty(consumerGroup)) {
      return true;
    }

    Map<String, ServiceProvider> allProviders = this.allProviders;
    String key = host + ":" + port;

    // 服务端有可能下线了，这种情况认为分组信息满足条件
    if (!allProviders.containsKey(key)) {
      return true;
    }

    ServiceProvider provider = allProviders.get(key);
    if (provider == null) {
      return true;
    }

    // 客户端有分组，服务端无分组，客户端不可以调用
    String providerGroup = provider.getGroup();
    providerGroup = StringUtils.trim(providerGroup);
    if (StringUtils.isEmpty(providerGroup)) {
      return false;
    }

    // A   A,B  A1,A2;B;C1,C2
    consumerGroup = consumerGroup.replace(';',',');
    consumerGroup = "," + consumerGroup + ",";

    providerGroup = "," + providerGroup + ",";
    if (consumerGroup.indexOf(providerGroup) >= 0) {
      return true;
    } else {
      return false;
    }
  }

  /**
   * 将一个传入服务器列表应用路由规则，返回过滤后的服务提供者列表
   */
  public Map<String, ServiceProvider> getProvidersAfterRoute(Map<String, ServiceProvider> serviceProviders) {
    Map<String, ServiceProvider> providers = new HashMap<String, ServiceProvider>(serviceProviders);

    for (Router route : routes) {
      providers = route.route(providers, consumerUrl);
    }

    return providers;
  }

  /**
   * 将serviceProviderMap的数据拷贝至一个providersForLoadBalance存储起来
   */
  private void generateProvidersForLB() {
    if (providersForLoadBalanceFlag != 0) {
      return;
    }
    providersForLoadBalanceFlag = 1;
    MapUtils.mapCopy(serviceProviderMap, providersForLoadBalance);
  }

  private final Runnable resolutionRunnable = new Runnable() {
    @Override
    public void run() {
      synchronized (lock) {
        resolveServerFunWithLock();
      }
    }
  };

  /**
   * 获取【经过负载均衡算法之后的服务提供者个数】
   *
   * @author sxp
   * @since 2018-5-16
   */
  public int getProvidersCount() {
    return providersCountAfterLoadBalance;
  }

  /**
   * 重新计算【经过负载均衡算法之后的服务提供者个数】
   *
   * @author sxp
   * @since 2018-8-31
   */
  public void reCalculateProvidersCountAfterLoadBalance(String method) {
    if (serviceProviderMap != null) {
      generateProvidersForLB();// 不需要每次请求时都调用路由规则过滤服务端列表
      loadBalancer(method);
      providersCountAfterLoadBalance = serviceProviderMap.size();
    } else {
      providersCountAfterLoadBalance = 0;
    }
  }

  /**
   * 带锁控制的服务器地址信息解析方法
   *
   * @since 2018-4-27 modify by sxp 解决空指针问题，并加锁做并发控制
   */
  public void resolveServerInfoWithLock() {
    // listener不能为空
    if (listener == null) {
      logger.info("listener is null, skip.");
      return;
    }

    synchronized (lock) {
      resolveServerFunWithLock();
    }
  }

  /**
   * 两个地方都调用这段代码，将代码抽取为一个方法
   *
   * @author sxp
   * @since V1.0 2017-4-19
   */
  private void resolveServerFunWithLock() {
    Listener savedListener;
    if (shutdown) {
      return;
    }
    savedListener = listener;
    resolving = true;

    try {
      boolean inBlackList = false;

      try {
        if (!useInitProvidersData) {
          getAllByName(serviceName);
        }

        if (serviceProviderMap == null || serviceProviderMap.size() == 0) {
          if (providersListener.isProviderListEmpty()) {
            providersCountAfterLoadBalance = 0;
            String msg = "注册中心上没有服务名称为[" + serviceName + "]的服务，请检查调用的服务接口名称是否正确！";
            throw new UnknownHostException(msg);
          }
        }

        // 应用过滤器
        applyFilter();

        if (serviceProviderMap != null) {
          providersCountAfterLoadBalance = serviceProviderMap.size();
        } else {
          providersCountAfterLoadBalance = 0;
        }

        if (serviceProviderMap == null || providersCountAfterLoadBalance == 0) {
          inBlackList = true;
          String msg = "注册中心上存在服务名称为[" + serviceName + "]的服务，但是当前客户端处于黑名单中，或者该服务未对当前客户端开放权限！";
          throw new UnknownHostException(msg);
        }
      } catch (UnknownHostException e) {
        if (shutdown) {
          return;
        }

        Status status = Status.UNAVAILABLE;
        if (inBlackList) {
          status = Status.PERMISSION_DENIED;// 黑名单使用一个更恰当的操作状态
        }

        savedListener.onError(status.withDescription(e.getMessage()).withCause(e));
        return;
      }

      //----begin----判定是否打印告警日志、提示服务已经有新版本上线----

      CheckDeprecatedService.check(serviceProviderMap);

      //----end----判定是否打印告警日志、提示服务已经有新版本上线----

      List<EquivalentAddressGroup> servers = new ArrayList<>();
      InetAddress inetAddr;
      InetSocketAddress inetSocketAddr;

      for (Map.Entry<String, ServiceProvider> entry : serviceProviderMap.entrySet()) {
        try {
          inetAddr = InetAddress.getByName(entry.getValue().getHost());
          inetSocketAddr = new InetSocketAddress(inetAddr, entry.getValue().getPort());
          servers.add(new EquivalentAddressGroup(inetSocketAddr));
        } catch (UnknownHostException e) {
          logger.error("解析服务提供者IP地址出错", e);
          // 应用过滤器之后，这里只剩下一个服务提供者了，所以出错后直接返回
          savedListener.onError(Status.UNAVAILABLE.withCause(e));
          return;
        }
      }

      savedListener.onAddresses(servers, Attributes.EMPTY);
    } finally {
      resolving = false;
    }
  }

  /**
   * 解析一个指定的服务端
   *
   * @author sxp
   * @since 2019/3/4
   */
  @Override
  public boolean resolveOneServer(EquivalentAddressGroup server) {
    if (listener == null) {
      logger.info("listener is null, skip.");
      return false;
    }

    List<EquivalentAddressGroup> servers = new ArrayList<>(1);
    servers.add(server);

    Listener savedListener = listener;

    try {
      savedListener.onAddresses(servers, Attributes.EMPTY);
    } catch (Throwable t) {
      logger.warn("解析一个指定的服务端出错", t);
      return false;
    }

    return true;
  }

  // To be mocked out in tests
  @VisibleForTesting
  public void getAllByName(String serviceName) {
    if (registry == null) {
      return;
    } else {
      Map<String, String> params = new ConcurrentHashMap<String, String>();
      params.put(GlobalConstants.Consumer.Key.INTERFACE, serviceName);
      List<URL> urls = registry.lookup(params);

      Map<String, ServiceProvider> newProviders = getProvidersByUrls(urls);

      serviceProviderMap.clear();
      serviceProviderMap.putAll(newProviders);

      applyRoute();// 需要根据路由规则过滤一下

      // 服务列表变化后，重置providersForLoadBalance
      providersForLoadBalance = new ConcurrentHashMap<String, ServiceProvider>();
      providersForLoadBalanceFlag = 0;
    }
  }


  /**
   * 根据监听到的URL组装服务提供者
   *
   * @author sxp
   * @since 2017-8-11
   * @since 2019-6-21 实现主备服务器自动切换（有主服务器的情况下，客户端只能调用主服务器；所有主服务器不可用时，客户端可以调用备服务器）
   * @since 2019-6-25 实现客户端服务端分组（客户端无分组：所有服务端可用；客户端有分组：只能选同一分组的服务端）
   */
  public Map<String, ServiceProvider> getProvidersByUrls(List<URL> urls) {
    String targetVersion;
    Map<String, ServiceProvider> providers = new HashMap<>();

    // 优先选择具有指定版本的服务(为空表示未指定版本)
    if (!StringUtils.isEmpty(serviceVersion)) {
      targetVersion = serviceVersion;
      providers = getProvidersFunc(urls, targetVersion);
    }

    // 如果注册中心没有该版本的服务，则不限制版本重新选择服务提供者
    if (providers.size() == 0) {
      targetVersion = "";
      providers = getProvidersFunc(urls, targetVersion);
    }

    return providers;
  }

  /**
   * 将两段相差不多的代码提取为一个方法
   *
   * @author sxp
   * @since 2019/7/3
   * @since 2019/10/21 先按照主备筛选服务器，再按照分组进行筛选
   */
  private Map<String, ServiceProvider> getProvidersFunc(List<URL> urls, String targetVersion) {
    boolean checkVersion = false;
    if (StringUtils.isNotEmpty(targetVersion)) {
      checkVersion = true;
    }
    Map<String, ServiceProvider> providers = new HashMap<>();

    String version, key;
    boolean hasMaster = false;
    ServiceProvider serviceProvider;
    Object masterObj, groupObj;

    for (URL url : urls) {
      if (!RegistryConstants.GRPC_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
        continue;
      }

      if (checkVersion) {
        version = url.getParameter(GlobalConstants.CommonKey.VERSION);
        if (version == null) {
          version = "";
        }
        if (!version.equals(targetVersion)) {
          continue;
        }
      }

      serviceProvider = new ServiceProvider();
      masterObj = ProvidersConfigUtils.getProperty(serviceName, url.getIp(), url.getPort(), GlobalConstants.CommonKey.MASTER);
      groupObj = ProvidersConfigUtils.getProperty(serviceName, url.getIp(), url.getPort(), GlobalConstants.CommonKey.GROUP);
      serviceProvider = serviceProvider.fromURL(url, masterObj, groupObj);

      ProvidersConfigUtils.resetServiceProviderProperties(serviceProvider);

      if (serviceProvider.getMaster()) {
        hasMaster = true;
      }

      key = serviceProvider.getHost() + ":" + serviceProvider.getPort();
      providers.put(key, serviceProvider);
    }

    Map<String, ServiceProvider> allProviders = new HashMap<>(providers);
    resetAllProviders(allProviders);// 备份：当前服务接口的所有提供者

    // 根据master标志进行筛选
    providers = chooseProvidersByMasterFlag(providers, hasMaster);

    // 根据分组进行筛选
    providers = selectProvidersByGroup(providers);

    logger.debug("available provider service:" + providers);

    return providers;
  }

  /**
   * 实现主备服务器自动切换
   * <p>
   * 有主服务器的情况下，客户端只能调用主服务器；所有主服务器不可用时，客户端可以调用备服务器
   * <p/>
   *
   * @author yulei
   * @since 2019/6/20
   * @since 2019/6/21 modify by sxp 微调
   */
  private static Map<String, ServiceProvider> chooseProvidersByMasterFlag(Map<String, ServiceProvider> providers, boolean hasMaster){
    ServiceProvider serviceProvider;
    Set<Map.Entry<String, ServiceProvider>> entrySet = providers.entrySet();

    Map<String, ServiceProvider> newProviders = new HashMap<>(MapUtils.capacity(providers.size()));

    for (Map.Entry<String, ServiceProvider> entry : entrySet) {
      serviceProvider = entry.getValue();
      if (hasMaster) {
        // 有主服务器的情况下，客户端只能调用主服务器
        if (serviceProvider.getMaster()) {
          newProviders.put(entry.getKey(), serviceProvider);
        }
      } else {
        // 没有主服务器，说明所有服务器都是备服务器，无需判断，直接放入服务器列表
        newProviders.put(entry.getKey(), serviceProvider);
      }
    }

    return newProviders;
  }


  /**
   * 根据分组筛选服务端列表
   *
   * <p>
   * <br>
   * 支持带优先级的服务分组功能，例如客户端的分组可以配置为: A1,A2;B1,B2;C1,C2    <br><br>
   *
   * 该配置的含义为：当前客户端优先访问分组为A1、A2的服务端。如果分组为A1、A2服务端不存在，访问分组为B1、B2的服务端；
   * 如果分组为B1、B2的服务端也不存在，访问分组为C1、C2的服务端；如果分组为C1、C2的服务端也不存在，客户端报错。 <br><br>
   *
   * 该配置的英文分号(;)用来将不同优先级的服务端隔开。A1,A2为第一优先级，B1,B2为第二优先级，C1,C2为第三优先级。
   * 客户端优先访问高优先级的服务端。
   * </p>
   *
   * @author yulei
   * @since 2019/8/16
   * @since 2019/8/21 modify by sxp 原来的代码逻辑比较复杂，换一种容易理解的方法来实现
   */
  private Map<String, ServiceProvider> selectProvidersByGroup(Map<String, ServiceProvider> providers) {
    if (StringUtils.isEmpty(group)) {
      return providers;
    }

    String[] groupsArray = group.split(";");
    int size = groupsArray.length;
    if (size == 0) {
      return providers;
    }

    Map<String, ServiceProvider> newProviders = new HashMap<>(MapUtils.capacity(providers.size()));
    Set<Map.Entry<String, ServiceProvider>> entrySet = providers.entrySet();

    String groups, group, serverGroup;
    String[] groupArr;
    ServiceProvider provider;
    boolean filtered;
    List<String> groupList = new ArrayList<>();

    for (int i = 0; i < size; i++) {
      groups = groupsArray[i];
      groups = StringUtils.trim(groups);
      if (StringUtils.isEmpty(groups)) {
        continue;
      }

      // 将当前优先级内的分组存放到groupList
      groupList.clear();
      groupArr = groups.split(",");

      for (int j = 0; j < groupArr.length; j++) {
        group = groupArr[j];
        group = StringUtils.trim(group);
        if (StringUtils.isNotEmpty(group)) {
          groupList.add(group);
        }
      }

      if (groupList.isEmpty()) {
        continue;
      }

      // 根据分组找服务端
      for (Map.Entry<String, ServiceProvider> entry : entrySet) {
        provider = entry.getValue();
        serverGroup = provider.getGroup();
        serverGroup = StringUtils.trim(serverGroup);

        if (StringUtils.isNotEmpty(serverGroup) && groupList.contains(serverGroup)) {
          // 判断当前服务端是否在黑名单中
          filtered = isInfilteredProviders(provider.getHost(), provider.getPort());
          if (!filtered) {
            newProviders.put(entry.getKey(), provider);
          }
        }
      }

      // 在当前优先级中找到服务端就不需要继续找了
      if (!newProviders.isEmpty()) {
        break;
      }
    }

    return newProviders;
  }

  @GuardedBy("this")
  private void resolve() {
    if (resolving || shutdown) {
      return;
    }
    executor.execute(resolutionRunnable);
  }

  @Override
  public final synchronized void shutdown() {
    if (shutdown) {
      return;
    }
    shutdown = true;
    if (timerService != null) {
      timerService = SharedResourceHolder.release(timerServiceResource, timerService);
    }
    if (executor != null) {
      executor = SharedResourceHolder.release(executorResource, executor);
    }

    //----begin----自动注销zk中的Consumer信息----dengjq

    if (findZkFuture != null) {
      findZkFuture.cancel(false);
    }
    if (findZkExecutor != null) {
      findZkExecutor.shutdown();
    }

    unRegistry();

    //----end----自动注销zk中的Consumer信息----
  }

  public List<Router> getRoutes() {
    return routes;
  }

  public void setRoutes(List<Router> routes) {
    this.routes = routes;
  }

  @Override
  public Map<String, ServiceProvider> getProvidersForLoadBalance() {
    return providersForLoadBalance;
  }

  public void setProvidersForLoadBalance(Map<String, ServiceProvider> newValue) {
    providersForLoadBalance = newValue;
  }

  public void setProvidersForLoadBalanceFlag(int providersForLoadBalanceFlag) {
    this.providersForLoadBalanceFlag = providersForLoadBalanceFlag;
  }

  public String getServiceVersion() {
    return serviceVersion;
  }

  public void setServiceVersion(String serviceVersion) {
    this.serviceVersion = serviceVersion;
  }

  public String getGroup(){
    return group;
  }

  public void setGroup(String group){
    this.group = group;
  }

  @Override
  public String getServiceName() {
    return serviceName;
  }

  public String getConsumerIP() {
    return consumerIP;
  }

  public Object getLock() {
    return lock;
  }

  @Override
  public String getSubscribeId() {
    return subscribeId;
  }

  @Override
  public ProvidersListener getProvidersListener() {
    return providersListener;
  }

  @Override
  public Map<String, ServiceProvider> getAllProviders() {
    return allProviders;
  }

  public void resetAllProviders(Map<String, ServiceProvider> allProviders) {
    this.allProviders.clear();
    this.allProviders.putAll(allProviders);
  }

  /**
   * 记录与之对应的ManagedChannel
   *
   * @author sxp
   * @since 2019/1/31
   */
  @Override
  public void setManagedChannel(ManagedChannel mc) {
    this.mc = mc;
  }

  @Override
  public ManagedChannel getManagedChannel() {
    return mc;
  }

  /**
   * 获取当前客户端的负载均衡策略集合
   *
   * @Author yuanzhonglin
   * @since 2019/4/16
   */
  @Override
  public Map<String, GlobalConstants.LB_STRATEGY> getLoadBlanceStrategyMap() {
    if (loadBlanceStrategyMap == null) {
      computeLoadBlanceStrategyMap();
    }
    return loadBlanceStrategyMap;
  }

  /**
   * 设置当前客户端的负载均衡策略集合
   *
   * @Author yuanzhonglin
   * @since 2019/4/16
   */
  @Override
  public void setLoadBlanceStrategyMap(Map<String, GlobalConstants.LB_STRATEGY> map) {
    this.loadBlanceStrategyMap = map;
  }

  /**
   * 计算当前服务的负载均衡策略集合
   *
   * @Author yuanzhonglin
   * @since 2019/4/16
   */
  public void computeLoadBlanceStrategyMap() {
    if (loadBlanceStrategyMap == null) {
      loadBlanceStrategyMap = new HashMap<>();
      String key = ConfigFileHelper.CONSUMER_KEY_PREFIX + GlobalConstants.CommonKey.DEFAULT_LOADBALANCE;
      String value = SystemConfig.getProperties().getProperty(key);
      loadBlanceStrategyMap.put(LoadBalanceUtil.EMPTY_METHOD, GlobalConstants.string2LB(value));
    }
  }

  /**
   * 不带锁的服务器地址信息解析方法
   *
   * @Author yuanzhonglin
   * @since 2019/4/17
   */
  public void resolveServerInfo(Object argument, String method) {
    // listener不能为空
    if (listener == null) {
      logger.info("listener is null, skip.");
      return;
    }

    this.listener.setArgument(argument);

    resolveServerFun(method);
  }

  /**
   * 简化版的服务器地址信息解析逻辑
   * <p>尽量减少锁的使用<p/>
   *
   * @Author yuanzhonglin
   * @since 2019/4/17
   */
  private void resolveServerFun(String method) {
    if (shutdown) {
      return;
    }

    Listener savedListener = listener;
    boolean inBlackList = false;

    try {
      if (!useInitProvidersData) {
        getAllByName(serviceName);
      }

      if (serviceProviderMap == null || serviceProviderMap.size() == 0) {
        if (providersListener.isProviderListEmpty()) {
          providersCountAfterLoadBalance = 0;
          String msg = "注册中心上没有服务名称为[" + serviceName + "]的服务，请检查调用的服务接口名称是否正确！";
          throw new UnknownHostException(msg);
        }
      }

      generateProvidersForLB();// 不需要每次请求时都调用路由规则过滤服务端列表
      loadBalancer(method);

      if (serviceProviderMap != null) {
        providersCountAfterLoadBalance = serviceProviderMap.size();
      } else {
        providersCountAfterLoadBalance = 0;
      }

      if (serviceProviderMap == null || providersCountAfterLoadBalance == 0) {
        inBlackList = true;
        String msg = "注册中心上存在服务名称为[" + serviceName + "]的服务，但是该服务未对当前客户端开放权限，或者该服务不可用！";
        throw new UnknownHostException(msg);
      }
    } catch (UnknownHostException e) {
      if (shutdown) {
        return;
      }

      Status status = Status.UNAVAILABLE;
      if (inBlackList) {
        status = Status.PERMISSION_DENIED;// 黑名单使用一个更恰当的操作状态
      }

      savedListener.onError(status.withDescription(e.getMessage()).withCause(e));
      return;
    }

    //----begin----判定是否打印告警日志、提示服务已经有新版本上线----

    CheckDeprecatedService.check(serviceProviderMap);

    //----end----判定是否打印告警日志、提示服务已经有新版本上线----

    List<EquivalentAddressGroup> servers = new ArrayList<>();
    InetAddress inetAddr;
    InetSocketAddress inetSocketAddr;

    for (Map.Entry<String, ServiceProvider> entry : serviceProviderMap.entrySet()) {
      try {
        inetAddr = InetAddress.getByName(entry.getValue().getHost());
        inetSocketAddr = new InetSocketAddress(inetAddr, entry.getValue().getPort());
        servers.add(new EquivalentAddressGroup(inetSocketAddr));
      } catch (UnknownHostException e) {
        logger.error("解析服务提供者IP地址出错", e);
        // 应用过滤器之后，这里只剩下一个服务提供者了，所以出错后直接返回
        savedListener.onError(Status.UNAVAILABLE.withCause(e));
        return;
      }
    }

    savedListener.onAddresses(servers, Attributes.EMPTY);
  }

  /**
   * 根据负载策略选择一台服务器
   *
   * @Author yuanzhonglin
   * @since 2019/4/17
   */
  private void loadBalancer(String method) {
    Preconditions.checkNotNull(providersForLoadBalance, "providersForLoadBalance");

    Object argument = this.listener.getArgument();

    LB_STRATEGY lb = LoadBalanceUtil.getLoadBalanceStrategy(loadBlanceStrategyMap, method);

    // loadBlanceStrategy已经计算好了，直接拿过来使用
    serviceProviderMap = LoadBalancerFactory.getServiceProviderByLbStrategy(
            lb, providersForLoadBalance, serviceName, argument);
  }

  /**
   * 删除客户端与离线服务端之间的无效subchannel
   *
   * @author sxp
   * @since 2019/12/02
   */
  public void removeInvalidCacheSubchannels(Set<String> removeHostPorts) {
    if (listener == null) {
      logger.info("listener is null, skip.");
      return;
    }

    listener.removeInvalidCacheSubchannels(removeHostPorts);
  }
}
