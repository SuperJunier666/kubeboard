package com.kubeboard.infrastructure.kubernetes;

import com.kubeboard.infrastructure.kubernetes.ClusterClientRegistry;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class KubernetesResourceAdapter {
    private static final Set<String> POD_PHASES = Set.of("Pending","Running","Succeded","Failed","Unknown");

    private final ClusterClientRegistry registry;

    public KubernetesResourceAdapter(ClusterClientRegistry registry){
        this.registry = registry;
    }

    public String getApiServerVersion(String clusterId) {
        return registry.getClient(clusterId).getKubernetesVersion().getGitVersion();
    }

    /** 保留整个 PodList，其中 metadata 包含 continue 和 resourceVersion。 */
    public PodList listPods(String clusterId, String namespace, PodListQuery query) {
        registry.requireAllowedNamespace(clusterId, namespace);
        PodListQuery effective = query == null ? PodListQuery.firstPage() : query;

        List<String> fieldSelectors = new ArrayList<>();
        if (effective.nodeName() != null) {
            fieldSelectors.add("spec.nodeName=" + effective.nodeName());
        }
        if (effective.phase() != null) {
            fieldSelectors.add("status.phase=" + effective.phase());
        }

        ListOptions options = new ListOptionsBuilder()
                .withLimit((long) effective.limit())
                .withContinue(effective.continueToken())
                .withLabelSelector(effective.labelSelector())
                .withFieldSelector(fieldSelectors.isEmpty() ? null : String.join(",", fieldSelectors))
                .build();

        return registry.getClient(clusterId)
                .pods().inNamespace(namespace).list(options);
    }

    public record PodListQuery(
            Integer limit,
            String continueToken,
            String labelSelector,
            String nodeName,
            String phase) {

        public PodListQuery {
            limit = limit == null ? 50 : limit;
            if (limit < 1 || limit > 200) {
                throw new IllegalArgumentException("limit must be between 1 and 200");
            }
            continueToken = blankToNull(continueToken);
            labelSelector = blankToNull(labelSelector);
            nodeName = blankToNull(nodeName);
            phase = blankToNull(phase);
            if (labelSelector != null && labelSelector.length() > 1024) {
                throw new IllegalArgumentException("labelSelector must not exceed 1024 characters");
            }
            if (nodeName != null) {
                requireResourceName(nodeName, "nodeName");
            }
            if (phase != null && !POD_PHASES.contains(phase)) {
                throw new IllegalArgumentException("Unsupported Pod phase");
            }
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static void requireResourceName(String value, String field) {
            if (value == null || value.length() > 253
                    || !value.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?")) {
                throw new IllegalArgumentException("Invalid " + field);
            }

}
