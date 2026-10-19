package com.kubeboard.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.List;


@Component
@Validated
@ConfigurationProperties(prefix = "dashboard")
public class DashboardProperties {
    private List<Cluster> clusters = List.of();
    private int connectionTimeoutMs = 5_000;
    private int requestTimeoutMs = 10_000;

    public List<Cluster> getClusters() {
        return clusters;
    }

    public void setClusters(List<Cluster> clusters) {
        this.clusters = clusters == null?List.of() :List.copyOf(clusters);
    }

    public int getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(int connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public static class Cluster{
        private String id;
        private String displayName;
        private String authMode = "kubeconfig";
        private Path kubeconfigPath;
        private String Context;
        private List<@NotBlank @Pattern(regexp = "[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?") String> allowedNamespaces = List.of();

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getAuthMode() {
            return authMode;
        }

        public void setAuthMode(String authMode) {
            this.authMode = authMode;
        }

        public Path getKubeconfigPath() {
            return kubeconfigPath;
        }

        public void setKubeconfigPath(Path kubeconfigPath) {
            this.kubeconfigPath = kubeconfigPath;
        }

        public String getContext() {
            return Context;
        }

        public void setContext(String context) {
            Context = context;
        }

        public List<String> getAllowedNamespaces() {
            return allowedNamespaces;
        }

        public void setAllowedNamespaces(List<String> allowedNamespaces) {
            this.allowedNamespaces = allowedNamespaces == null
                    ? List.of() : List.copyOf(allowedNamespaces);
        }
    }


}
