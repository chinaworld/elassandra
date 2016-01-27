package org.apache.cassandra.service;

import static com.google.common.collect.Sets.newHashSet;
import static org.elasticsearch.common.settings.Settings.Builder.EMPTY_SETTINGS;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.config.Schema;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.Version;
import org.elasticsearch.bootstrap.Bootstrap;
import org.elasticsearch.bootstrap.JVMCheck;
import org.elasticsearch.bootstrap.JarHell;
import org.elasticsearch.cassandra.SchemaService;
import org.elasticsearch.cassandra.discovery.CassandraDiscovery;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.inject.CreationException;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.spi.Message;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.logging.logback.LogbackESLoggerFactory;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.monitor.process.ProcessProbe;
import org.elasticsearch.node.Node;
import org.elasticsearch.node.NodeBuilder;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap sequence Cassandra setup() joinRing() beforBoostrap() -> wait for
 * local shard = STARTED ElasticSearch activate() GatewayService recover
 * metadata from cassandra schema DiscoveryService discover ring topology and
 * build routing table await that Cassandra start() (Complet cassandra
 * bootstrap) ElasticSearch start() (Open Elastic http service)
 * 
 * 
 * @author vroyer
 *
 */
public class ElassandraDaemon extends CassandraDaemon {
    private static final Logger logger = LoggerFactory.getLogger(ElassandraDaemon.class);

    private static volatile Thread keepAliveThread;
    private static volatile CountDownLatch keepAliveLatch;

    public static ElassandraDaemon instance = new ElassandraDaemon();
    
    private Node node = null;
    private Settings settings;
    private Environment env;
    private boolean addShutdownHook;

    static {
        try {
            ESLoggerFactory.setDefaultFactory(new LogbackESLoggerFactory());
        } catch (Exception e) {
            logger.error("Failed to configure logging",e);
        }
    }

    public ElassandraDaemon() {
        super();
    }

    public void activate(boolean addShutdownHook) {
        this.addShutdownHook = addShutdownHook;
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            mbs.registerMBean(new StandardMBean(new NativeAccess(), NativeAccessMBean.class), new ObjectName(MBEAN_NAME));
        } catch (Exception e) {
            logger.error("error registering MBean {}", MBEAN_NAME, e);
            // Allow the server to start even if the bean can't be registered
        }
        super.setup(); // start bootstrap CassandraDaemon2 and call beforeRecover()+beforeBootstrap() to activate ElasticSearch
        super.start(); // complete cassandra start
        instance.node.start(); // start ElasticSerach public services to complete
    }

   
 
    @Override
    public void beforeRecover() {
        try {
            KSMetaData ksMetaData = Schema.instance.getKSMetaData(SchemaService.ELASTIC_ADMIN_KEYSPACE);
            if (ksMetaData != null) {
                logger.debug("Starting ElasticSearch before recovering commitlogs (elastic_admin already keyspace exists)");
                startElasticSearch();
             }
        } catch(Exception e) {
            logger.warn("Unexpected error",e);
        }
        
    }
    
    @Override
    public void beforeBootstrap() {
        if (instance.node == null) {
            logger.debug("Starting ElasticSearch before bootstraping (create elastic_admin keyspace)");
            startElasticSearch();
        } 
    }
    
    @Override
    public void beforeStartupComplete() {
        if (instance.node == null) {
            logger.debug("Starting ElasticSearch before startup complete (create elastic_admin keyspace, but no boostrap)");
            startElasticSearch();
        } 
    }
    
    private void startElasticSearch() {
        instance.setup(addShutdownHook, settings, env); // Initialize ElasticSearch by reading stored metadata.
        instance.node.activate(); // block until recovery and all local primary shards are started.
        logger.debug("ElasticSearch ready to index (but not search), client={}", this.node.client());
    }

    
    /**
     * hook for JSVC
     */
    public void start() {
        super.start();
    }

    /**
     * hook for JSVC
     */
    public void stop() {
        node.close();
        super.stop();
    }

    /**
     * hook for JSVC
     */
    public void init(String[] args) {

    }

    /**
     * hook for JSVC
     */
    public void activate() {
        node.activate();
    }

    /**
     * hook for JSVC
     */
    public void destroy() {
        super.destroy();
        node.close();
        keepAliveLatch.countDown();
    }

    private void setup(boolean addShutdownHook, Settings settings, Environment environment) {
        org.elasticsearch.bootstrap.Bootstrap.initializeNatives(environment.tmpFile(),
                          settings.getAsBoolean("bootstrap.mlockall", false),
                          settings.getAsBoolean("bootstrap.seccomp", true),
                          settings.getAsBoolean("bootstrap.ctrlhandler", true));

        // initialize probes before the security manager is installed
        org.elasticsearch.bootstrap.Bootstrap.initializeProbes();

        if (addShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    if (node != null) {
                        node.close();
                    }
                }
            });
        }

        // look for jar hell
        /*
        try {
            JarHell.checkJarHell();
        } catch (Exception e) {
            logger.error(e.getMessage(),e);
        }
         */
        
        // install SM after natives, shutdown hooks, etc.
        //org.elasticsearch.bootstrap.Bootstrap.setupSecurity(settings, environment);

        // We do not need to reload system properties here as we have already applied them in building the settings and
        // reloading could cause multiple prompts to the user for values if a system property was specified with a prompt
        // placeholder
        Settings nodeSettings = Settings.settingsBuilder()
                .put(settings)
                .put(InternalSettingsPreparer.IGNORE_SYSTEM_PROPERTIES_SETTING, true)
                .build();

        NodeBuilder nodeBuilder = NodeBuilder.nodeBuilder().settings(nodeSettings);
        nodeBuilder.clusterName(DatabaseDescriptor.getClusterName()).data(true).settings()
                .put("path.home", System.getenv("CASSANDRA_HOME"))
                .put("name", CassandraDiscovery.buildNodeName(DatabaseDescriptor.getRpcAddress()))
                .put("network.host", DatabaseDescriptor.getRpcAddress().getHostAddress())
                .put("http.netty.bind_host", DatabaseDescriptor.getRpcAddress().getHostAddress())
                .put("http.bind_host", DatabaseDescriptor.getRpcAddress().getHostAddress())
                .put("http.host", DatabaseDescriptor.getRpcAddress().getHostAddress())
                ;

        this.node = nodeBuilder.build();
    }
  
    public static Client client() {
        if ((instance.node != null) && (!instance.node.isClosed()))
            return instance.node.client();
        return null;
    }

    public static Injector injector() {
        if ((instance.node != null) && (!instance.node.isClosed()))
            return instance.node.injector();
        return null;
    }

    public static void main(String[] args) {
        
        boolean foreground = System.getProperty("cassandra-foreground") != null;
        // handle the wrapper system property, if its a service, don't run as a
        // service
        if (System.getProperty("wrapper.service", "XXX").equalsIgnoreCase("true")) {
            foreground = false;
        }

        try {
            instance.env = InternalSettingsPreparer.prepareEnvironment(
                    Settings.settingsBuilder()
                        .put("path.home",System.getenv("CASSANDRA_HOME"))
                        .put("path.conf",System.getenv("CASSANDRA_CONF"))
                        .build(), 
                    foreground ? Terminal.DEFAULT : null);
            instance.settings = instance.env.settings();
        } catch (Exception e) {
            String errorMessage = buildErrorMessage("Setup", e);
            System.err.println(errorMessage);
            System.err.flush();
            System.exit(3);
        }

        if (System.getProperty("es.max-open-files", "false").equals("true")) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            logger.info("max_open_files [{}]", ProcessProbe.getInstance().getMaxFileDescriptorCount());
        }

        // warn if running using the client VM
        if (JvmInfo.jvmInfo().getVmName().toLowerCase(Locale.ROOT).contains("client")) {
            ESLogger logger = Loggers.getLogger(Bootstrap.class);
            logger.warn("jvm uses the client vm, make sure to run `java` with the server vm for best performance by adding `-server` to the command line");
        }

        String stage = "Initialization";

        try {
            if (!foreground) {
                Loggers.disableConsoleLogging();
                System.out.close();
            }

            // fail if using broken version
            JVMCheck.check();

            instance.activate(true);

            if (!foreground) {
                System.err.close();
            }

            keepAliveLatch = new CountDownLatch(1);
            // keep this thread alive (non daemon thread) until we shutdown
            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    keepAliveLatch.countDown();
                }
            });

            keepAliveThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        keepAliveLatch.await();
                    } catch (InterruptedException e) {
                        // bail out
                    }
                }
            }, "elasticsearch[keepAlive/" + Version.CURRENT + "]");
            keepAliveThread.setDaemon(false);
            keepAliveThread.start();
        } catch (Throwable e) {
            ESLogger logger = Loggers.getLogger(ElassandraDaemon.class);
            if (instance.node != null) {
                logger = Loggers.getLogger(ElassandraDaemon.class, instance.node.settings().get("name"));
            }
            String errorMessage = buildErrorMessage(stage, e);
            if (foreground) {
                System.err.println(errorMessage);
                System.err.flush();
                Loggers.disableConsoleLogging();
            }
            logger.error("Exception", e);

            System.exit(3);
        }
    }

    private static String buildErrorMessage(String stage, Throwable e) {
        StringBuilder errorMessage = new StringBuilder("{").append(Version.CURRENT).append("}: ");
        errorMessage.append(stage).append(" Failed ...\n");
        if (e instanceof CreationException) {
            CreationException createException = (CreationException) e;
            Set<String> seenMessages = newHashSet();
            int counter = 1;
            for (Message message : createException.getErrorMessages()) {
                String detailedMessage;
                if (message.getCause() == null) {
                    detailedMessage = message.getMessage();
                } else {
                    detailedMessage = ExceptionsHelper.detailedMessage(message.getCause(), true, 0);
                }
                if (detailedMessage == null) {
                    detailedMessage = message.getMessage();
                }
                if (seenMessages.contains(detailedMessage)) {
                    continue;
                }
                seenMessages.add(detailedMessage);
                errorMessage.append("").append(counter++).append(") ").append(detailedMessage);
            }
        } else {
            errorMessage.append("- ").append(ExceptionsHelper.detailedMessage(e, true, 0));
        }
        if (Loggers.getLogger(ElassandraDaemon.class).isDebugEnabled()) {
            errorMessage.append("\n").append(ExceptionsHelper.stackTrace(e));
        }
        return errorMessage.toString();
    }
}
