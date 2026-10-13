package com.kubeboard.infrastructure.kubernetes;

import com.kubeboard.config.DashboardProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Component
public class ClusterClientRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ClusterClientRegistry.class);
    private final Map<String,ClusterSpec> clusters;
    private final Map<String, KubernetesClient> clients = new HashMap<>();
    private final int connectionTimeoutMs;
    private final int requestTimeoutMs;
    private boolean closed;

    public ClusterClientRegistry(DashboardProperties properties){
        Map<String, ClusterSpec>  configured =new LinkedHashMap<>();
        for(DashboardProperties.Cluster cluster : properties.getClusters()){
            ClusterSpec spec = new ClusterSpec(cluster.getKubeconfigPath().toAbsolutePath().normalize(),
                                               cluster.getContext(),Set.copyOf(cluster.getAllowedNamespaces()));
            if (configured.putIfAbsent(cluster.getId(),spec) != null){
                throw new RuntimeException("Duplicate configured clusterId: " + cluster.getId());
            }
        }
        // 根据clusterId获取getKubeconfigPath等配置
        this.clusters = Map.copyOf(configured);
        this.connectionTimeoutMs = properties.getConnectionTimeoutMs();
        this.requestTimeoutMs = properties.getRequestTimeoutMs();
    }

    public synchronized KubernetesClient getClient(String clusterId){
        if (closed){
            throw new IllegalStateException("Kubernetes client registry is closed");
        }
        ClusterSpec spec = requireCluster(clusterId);
        KubernetesClient existing = clients.get(clusterId);
        if (existing != null){
            return existing;
        }
        KubernetesClient created = createClient(clusterId,spec);
        clients.put(clusterId,created);
        return created;
    }

    private KubernetesClient createClient(String clusterId,ClusterSpec spec){
        try {
//            String contents = Files.readString(spec.kubeconfigPath(), StandardCharsets.UTF_8);
            Config config = Config.fromKubeconfig(spec.context(), spec.kubeconfigPath().toFile());
            config.setRequestTimeout(requestTimeoutMs);
            config.setConnectionTimeout(connectionTimeoutMs);
            config.setRequestRetryBackoffLimit(0);
            config.setTrustCerts(false);
            return new KubernetesClientBuilder().withConfig(config).build();
        } catch ( RuntimeException exception){
            throw new IllegalStateException( "Cannot initialize configured Kubernetes client: " + clusterId);
        }
    }

   //  根据clusterId获取spec
    private ClusterSpec requireCluster(String clusterId){
        ClusterSpec spec = clusterId  == null?null : clusters.get(clusterId);
        if(spec == null){
            throw new IllegalArgumentException("Unknown configured clusterId");
        }
        return spec;
    }




    private record ClusterSpec(Path kubeconfigPath, String context, Set<String> allowedNamespaces) {
    }
}
