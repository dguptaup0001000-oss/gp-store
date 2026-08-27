package com.gpstore.config;

import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Live snapshot of the <em>main</em> Tomcat executor (catalog/API traffic).
 *
 * The optional live-health connector has its own eight-thread pool and is
 * excluded on purpose: saturating shop traffic must not make
 * {@code /api/health/live} wait behind eighty busy catalog workers.
 */
@Component
public class TomcatRequestCapacity implements ApplicationListener<WebServerInitializedEvent> {

    private final int livePort;
    private volatile ThreadPoolExecutor mainExecutor;
    private volatile int mainMaxConnections = -1;

    public TomcatRequestCapacity(
            @Value("${server.tomcat.live-port:0}") int livePort) {
        this.livePort = livePort;
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        WebServer server = event.getWebServer();
        if (!(server instanceof TomcatWebServer tomcat)) {
            return;
        }
        for (Connector connector : tomcat.getTomcat().getService().findConnectors()) {
            if (livePort > 0 && connector.getPort() == livePort) {
                continue;
            }
            Executor executor = connector.getProtocolHandler().getExecutor();
            if (executor instanceof ThreadPoolExecutor pool) {
                mainExecutor = pool;
            }
            Object maxConnections = connector.getProperty("maxConnections");
            if (maxConnections instanceof Number number) {
                mainMaxConnections = number.intValue();
            }
        }
    }

    /**
     * True when every catalog worker is already running a request. The next
     * catalog GET that wins a thread should 503 rather than start another
     * JSON serialize onto a 2-vCPU host that is already at capacity.
     */
    public boolean isMainPoolSaturated() {
        ThreadPoolExecutor pool = mainExecutor;
        if (pool == null) {
            return false;
        }
        return pool.getActiveCount() >= pool.getMaximumPoolSize()
                && pool.getMaximumPoolSize() > 0;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> body = new LinkedHashMap<>();
        ThreadPoolExecutor pool = mainExecutor;
        if (pool != null) {
            body.put("tomcatThreadsBusy", pool.getActiveCount());
            body.put("tomcatThreadsCurrent", pool.getPoolSize());
            body.put("tomcatThreadsMax", pool.getMaximumPoolSize());
            body.put("tomcatQueue", pool.getQueue() != null ? pool.getQueue().size() : 0);
        }
        if (mainMaxConnections > 0) {
            body.put("tomcatMaxConnections", mainMaxConnections);
        }
        long gcCount = 0;
        long gcTimeMs = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) {
                gcCount += count;
            }
            if (time > 0) {
                gcTimeMs += time;
            }
        }
        body.put("gcCount", gcCount);
        body.put("gcTimeMs", gcTimeMs);
        var os = ManagementFactory.getOperatingSystemMXBean();
        body.put("systemLoadAverage", os.getSystemLoadAverage());
        if (os instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            body.put("processCpuLoad", sunOs.getProcessCpuLoad());
            body.put("systemCpuLoad", sunOs.getCpuLoad());
        }
        return body;
    }
}
