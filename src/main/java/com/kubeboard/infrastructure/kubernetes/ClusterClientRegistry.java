package com.kubeboard.infrastructure.kubernetes;

import com.kubeboard.config.DashboardProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
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


/**
 * 集群客户端注册表。
 * 职责:
 * 1. 根据配置文件中声明的集群列表,保存每个集群的连接配置(kubeconfig 路径、context、允许访问的命名空间);
 * 2. 按 clusterId 懒加载并缓存 KubernetesClient,同一个集群只会创建一个客户端;
 * 3. 校验 clusterId 和 namespace 是否在配置范围内,防止访问未授权的集群或命名空间;
 * 4. 应用关闭时统一释放所有客户端。
 */
@Component
public class ClusterClientRegistry implements AutoCloseable{
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

    /**
     * 获取指定集群的客户端;如果还没创建,则懒加载创建并缓存。
     *
     * @param clusterId 集群 id,必须是配置中声明过的
     * @return 该集群对应的客户端(同一个集群始终返回同一个实例)
     */
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

    /**
     * 根据clusterId获取spec
     *
     * @throws IllegalArgumentException clusterId 为 null 或不在配置中
     */
    private ClusterSpec requireCluster(String clusterId){
        ClusterSpec spec = clusterId  == null?null : clusters.get(clusterId);
        if(spec == null){
            throw new IllegalArgumentException("Unknown configured clusterId");
        }
        return spec;
    }

    public void requireAllowedNamespaces(String clusterId,String namespace){
        ClusterSpec spec = requireCluster(clusterId);
        if (namespace == null || !spec.allowedNamespaces().contains(namespace)){
            throw new IllegalArgumentException("Namespace is outside the configured cluster scope");
        }
    }

    @Override
    @PreDestroy
    public synchronized void close(){
        if (closed){
            return;
        }
        closed = true;
        clients.forEach((clusterId,client)->{
            try {
                client.close();
            } catch (RuntimeException exception) {
                LOG.warn("Failed to close Kubernetes client for cluster {}", clusterId);
            }
        });
        clients.clear();
    }

    private record ClusterSpec(Path kubeconfigPath, String context, Set<String> allowedNamespaces) {
    }
}
