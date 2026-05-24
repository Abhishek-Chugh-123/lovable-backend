////package com.codingshuttle.projects.lovable_clone.service.impl;
////
////import com.codingshuttle.projects.lovable_clone.dto.deploy.DeployResponse;
////import com.codingshuttle.projects.lovable_clone.service.DeploymentService;
////import io.fabric8.kubernetes.api.model.Pod;
////import io.fabric8.kubernetes.client.KubernetesClient;
////import io.fabric8.kubernetes.client.dsl.ExecListener;
////import io.fabric8.kubernetes.client.dsl.ExecWatch;
////import lombok.RequiredArgsConstructor;
////import lombok.extern.slf4j.Slf4j;
////import org.springframework.data.redis.core.StringRedisTemplate;
////import org.springframework.stereotype.Service;
////
////import java.io.ByteArrayOutputStream;
////import java.util.concurrent.CompletableFuture;
////import java.util.concurrent.TimeUnit;
////
////@Service
////@RequiredArgsConstructor
////@Slf4j
////public class KubernetesDeploymentServiceImpl implements DeploymentService {
////
////    private final KubernetesClient client;
////    private final StringRedisTemplate redisTemplate;
////
////    private static final String NAMESPACE = "shuttle-apps";
////    private static final String POOL_LABEL = "status";
////    private static final String PROJECT_LABEL = "project-id";
////    private static final String IDLE = "idle";
////    private static final String BUSY = "busy";
////    private static final String SYNCER_CONTAINER = "syncer";
////    private static final String RUNNER_CONTAINER = "runner";
////    private static final String REVERSE_PROXY_PORT = "8090";
////
////    @Override
////    public DeployResponse deploy(Long projectId) {
////
////        String domain = "project-" + projectId + ".app.domain.com";
////        log.info("🚀 Deploy request received for projectId={}, domain={}", projectId, domain);
////
////        Pod existingPod = findActivePod(projectId);
////
////        if(existingPod != null) {
////            log.info("♻️ Reusing existing pod {} for project {}",
////                    existingPod.getMetadata().getName(), projectId);
////
////            registerRoute(domain, existingPod);
////            return new DeployResponse("http://" + domain + ":" + REVERSE_PROXY_PORT);
////        }
////
////        log.info("🆕 No active pod found. Claiming new pod...");
////        return claimAndStartNewPod(projectId, domain);
////    }
////
////    private DeployResponse claimAndStartNewPod(Long projectId, String domain) {
////
////        log.info("🔍 Searching for idle pods...");
////
////        Pod pod = client.pods().inNamespace(NAMESPACE)
////                .withLabel(POOL_LABEL, IDLE)
////                .list().getItems().stream()
////                .findFirst()
////                .orElseThrow(() -> new RuntimeException("❌ No idle runners available"));
////
////        String podName = pod.getMetadata().getName();
////        log.info("✅ Found idle pod: {}. Assigning to project {}", podName, projectId);
////
////        client.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
////            p.getMetadata().getLabels().put(POOL_LABEL, BUSY);
////            p.getMetadata().getLabels().put(PROJECT_LABEL, projectId.toString());
////            return p;
////        });
////
////        try {
////            log.info("📦 Starting sync + dev server for pod {}", podName);
////
////            // Initial Sync
////            String initialSyncCmd = String.format(
////                    "mc mirror --overwrite myminio/projects/%d/ /app/",
////                    projectId);
////
////            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", initialSyncCmd);
////
////            // Watch Sync
////            String watchCmd = String.format(
////                    "nohup mc mirror --overwrite --watch myminio/projects/%d/ /app/ > /app/sync.log 2>&1 &",
////                    projectId);
////
////            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", watchCmd);
////
////            // Start Dev Server
////            String startCmd = "npm install && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &";
////
////            execCommand(podName, RUNNER_CONTAINER, "sh", "-c", startCmd);
////
////            // 🔥 IMPORTANT FIX: fetch updated pod for correct IP
////            Pod updatedPod = client.pods()
////                    .inNamespace(NAMESPACE)
////                    .withName(podName)
////                    .get();
////
////            String podIp = updatedPod.getStatus().getPodIP();
////            log.info("🌐 Pod IP fetched: {}", podIp);
////
////            registerRoute(domain, updatedPod);
////
////            log.info("✅ Deployment successful → http://{}:{}", domain, REVERSE_PROXY_PORT);
////
////            return new DeployResponse("http://" + domain + ":" + REVERSE_PROXY_PORT);
////
////        } catch(Exception e) {
////            log.error("❌ Deployment failed for project {}. Releasing pod {}", projectId, podName, e);
////            client.pods().inNamespace(NAMESPACE).withName(podName).delete();
////            throw new RuntimeException("Failed to deploy project " + projectId);
////        }
////    }
////
////    private void registerRoute(String domain, Pod pod) {
////
////        String podIp = pod.getStatus().getPodIP();
////
////        log.info("📡 Registering route: domain={} -> podIp={}", domain, podIp);
////
////        if (podIp == null) {
////            log.error("❌ Pod IP is NULL for domain={}", domain);
////            throw new RuntimeException("Pod has no IP!");
////        }
////
////        try {
////            redisTemplate.opsForValue().set(
////                    "route:" + domain,
////                    podIp + ":5173",
////                    6,
////                    TimeUnit.HOURS
////            );
////
////            log.info("✅ Route stored in Redis: route:{} -> {}", domain, podIp + ":5173");
////
////        } catch (Exception e) {
////            log.error("❌ Redis write FAILED for domain={}", domain, e);
////            throw e;
////        }
////    }
////
////    private void execCommand(String podName, String container, String... command) {
////
////        log.info("⚙️ Exec in pod={} container={} cmd={}",
////                podName, container, String.join(" ", command));
////
////        CompletableFuture<String> data = new CompletableFuture<>();
////
////        try (ExecWatch ignored = client.pods().inNamespace(NAMESPACE).withName(podName)
////                .inContainer(container)
////                .writingOutput(new ByteArrayOutputStream())
////                .writingError(new ByteArrayOutputStream())
////                .usingListener(new ExecListener() {
////                    @Override
////                    public void onClose(int code, String reason) {
////                        log.info("✅ Exec completed with code={} reason={}", code, reason);
////                        data.complete("Done");
////                    }
////                })
////                .exec(command)) {
////
////            if (command[command.length - 1].trim().endsWith("&")) {
////                Thread.sleep(500);
////            } else {
////                data.get(30, TimeUnit.SECONDS);
////            }
////
////        } catch (Exception e) {
////            log.error("❌ Exec failed in pod={} container={}", podName, container, e);
////            throw new RuntimeException("Pod Execution Failed", e);
////        }
////    }
////
////    Pod findActivePod(Long projectId) {
////        return client.pods().inNamespace(NAMESPACE)
////                .withLabel(PROJECT_LABEL, projectId.toString())
////                .withLabel(POOL_LABEL, BUSY)
////                .list().getItems().stream()
////                .filter(pod -> pod.getStatus().getPhase().equals("Running"))
////                .findFirst()
////                .orElse(null);
////    }
////}
//package com.codingshuttle.projects.lovable_clone.service.impl;
//
//import com.codingshuttle.projects.lovable_clone.dto.deploy.DeployResponse;
//import com.codingshuttle.projects.lovable_clone.service.DeploymentService;
//import io.fabric8.kubernetes.api.model.Pod;
//import io.fabric8.kubernetes.client.KubernetesClient;
//import io.fabric8.kubernetes.client.dsl.ExecListener;
//import io.fabric8.kubernetes.client.dsl.ExecWatch;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.redis.core.StringRedisTemplate;
//import org.springframework.stereotype.Service;
//
//import java.io.ByteArrayOutputStream;
//import java.util.concurrent.CompletableFuture;
//import java.util.concurrent.TimeUnit;
//
//@Service
//@RequiredArgsConstructor
//@Slf4j
//public class KubernetesDeploymentServiceImpl implements DeploymentService {
//
//    private final KubernetesClient client;
//    private final StringRedisTemplate redisTemplate;
//
//    private static final String NAMESPACE = "shuttle-apps";
//    private static final String POOL_LABEL = "status";
//    private static final String PROJECT_LABEL = "project-id";
//    private static final String IDLE = "idle";
//    private static final String BUSY = "busy";
//    private static final String SYNCER_CONTAINER = "syncer";
//    private static final String RUNNER_CONTAINER = "runner";
//    private static final String REVERSE_PROXY_PORT = "8090";
//
//    // Wait time for background commands (npm install can take time)
//    private static final int BACKGROUND_CMD_WAIT_MS = 15000;
//
//    @Override
//    public DeployResponse deploy(Long projectId) {
//
//        String domain = "project-" + projectId + ".app.domain.com";
//        log.info("🚀 Deploy request received for projectId={}, domain={}", projectId, domain);
//
//        Pod existingPod = findActivePod(projectId);
//
//        if (existingPod != null) {
//            String podName = existingPod.getMetadata().getName();
//            log.info("♻️ Reusing existing pod {} for project {}", podName, projectId);
//
//            // Re-start sync watcher in case pod restarted
//            String watchCmd = String.format(
//                    "nohup mc mirror --overwrite --watch myminio/projects/%d/ /app/ > /app/sync.log 2>&1 &",
//                    projectId);
//            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", watchCmd);
//
//            // Re-start Vite in case pod restarted — safe to run even if already running
//            String startCmd = "npm install && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &";
//            execCommand(podName, RUNNER_CONTAINER, "sh", "-c", startCmd);
//
//            // Fetch fresh pod object to get latest IP
//            Pod freshPod = client.pods()
//                    .inNamespace(NAMESPACE)
//                    .withName(podName)
//                    .get();
//
//            registerRoute(domain, freshPod);
//            return new DeployResponse("http://" + domain + ":" + REVERSE_PROXY_PORT);
//        }
//
//        log.info("🆕 No active pod found. Claiming new pod...");
//        return claimAndStartNewPod(projectId, domain);
//    }
//
//    private DeployResponse claimAndStartNewPod(Long projectId, String domain) {
//
//        log.info("🔍 Searching for idle pods...");
//
//        Pod pod = client.pods().inNamespace(NAMESPACE)
//                .withLabel(POOL_LABEL, IDLE)
//                .list().getItems().stream()
//                .filter(p -> "Running".equals(p.getStatus().getPhase())) // YAHAN ADD KARO
//                .filter(p -> p.getStatus().getConditions().stream()
//                        .anyMatch(c -> "Ready".equals(c.getType())
//                                && "True".equals(c.getStatus()))) // AUR YAHAN
//                .findFirst()
//                .orElseThrow(() -> new RuntimeException("❌ No idle runners available"));
//
//        String podName = pod.getMetadata().getName();
//        log.info("✅ Found idle pod: {}. Assigning to project {}", podName, projectId);
//
//        // Mark pod as busy and assign project
//        client.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
//            p.getMetadata().getLabels().put(POOL_LABEL, BUSY);
//            p.getMetadata().getLabels().put(PROJECT_LABEL, projectId.toString());
//            return p;
//        });
//
//        try {
//            log.info("📦 Starting sync + dev server for pod {}", podName);
//
//            // Step 1: Initial full sync from MinIO
//            String initialSyncCmd = String.format(
//                    "mc mirror --overwrite myminio/projects/%d/ /app/",
//                    projectId);
//            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", initialSyncCmd);
//
//            // Step 2: Start watch sync (background)
//            String watchCmd = String.format(
//                    "nohup mc mirror --overwrite --watch myminio/projects/%d/ /app/ > /app/sync.log 2>&1 &",
//                    projectId);
//            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", watchCmd);
//
//            // Step 3: Install dependencies and start Vite dev server (background)
//            String startCmd = "npm install && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &";
//            execCommand(podName, RUNNER_CONTAINER, "sh", "-c", startCmd);
//
//            // Step 4: Fetch updated pod for correct IP
//            Pod updatedPod = client.pods()
//                    .inNamespace(NAMESPACE)
//                    .withName(podName)
//                    .get();
//
//            String podIp = updatedPod.getStatus().getPodIP();
//            log.info("🌐 Pod IP fetched: {}", podIp);
//
//            // Step 5: Register route in Redis
//            registerRoute(domain, updatedPod);
//
//            log.info("✅ Deployment successful → http://{}:{}", domain, REVERSE_PROXY_PORT);
//
//            return new DeployResponse("http://" + domain + ":" + REVERSE_PROXY_PORT);
//
//        } catch (Exception e) {
//            log.error("❌ Deployment failed for project {}. Releasing pod {}", projectId, podName, e);
//
//            // Reset pod labels back to idle so it can be reused
//            try {
//                client.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
//                    p.getMetadata().getLabels().put(POOL_LABEL, IDLE);
//                    p.getMetadata().getLabels().remove(PROJECT_LABEL);
//                    return p;
//                });
//            } catch (Exception releaseEx) {
//                log.error("❌ Failed to release pod {} back to idle pool", podName, releaseEx);
//                // Pod is in bad state — delete it so Kubernetes recreates a fresh one
//                client.pods().inNamespace(NAMESPACE).withName(podName).delete();
//            }
//
//            throw new RuntimeException("Failed to deploy project " + projectId);
//        }
//    }
//
//    private void registerRoute(String domain, Pod pod) {
//
//        String podIp = pod.getStatus().getPodIP();
//
//        log.info("📡 Registering route: domain={} -> podIp={}", domain, podIp);
//
//        if (podIp == null) {
//            log.error("❌ Pod IP is NULL for domain={}", domain);
//            throw new RuntimeException("Pod has no IP!");
//        }
//
//        try {
//            redisTemplate.opsForValue().set(
//                    "route:" + domain,
//                    podIp + ":5173",
//                    6,
//                    TimeUnit.HOURS
//            );
//
//            log.info("✅ Route stored in Redis: route:{} -> {}", domain, podIp + ":5173");
//
//        } catch (Exception e) {
//            log.error("❌ Redis write FAILED for domain={}", domain, e);
//            throw e;
//        }
//    }
//
//    private void execCommand(String podName, String container, String... command) {
//
//        log.info("⚙️ Exec in pod={} container={} cmd={}",
//                podName, container, String.join(" ", command));
//
//        CompletableFuture<String> data = new CompletableFuture<>();
//
//        try (ExecWatch ignored = client.pods().inNamespace(NAMESPACE).withName(podName)
//                .inContainer(container)
//                .writingOutput(new ByteArrayOutputStream())
//                .writingError(new ByteArrayOutputStream())
//                .usingListener(new ExecListener() {
//                    @Override
//                    public void onClose(int code, String reason) {
//                        log.info("✅ Exec completed with code={} reason={}", code, reason);
//                        data.complete("Done");
//                    }
//                })
//                .exec(command)) {
//
//            boolean isBackgroundCmd = command[command.length - 1].trim().endsWith("&");
//
//            if (isBackgroundCmd) {
//                // Background command — wait for npm install + Vite to be ready
//                log.info("⏳ Background command detected, waiting {}ms...", BACKGROUND_CMD_WAIT_MS);
//                Thread.sleep(BACKGROUND_CMD_WAIT_MS);
//            } else {
//                // Foreground command — wait for it to complete
//                data.get(60, TimeUnit.SECONDS);
//            }
//
//        } catch (Exception e) {
//            log.error("❌ Exec failed in pod={} container={}", podName, container, e);
//            throw new RuntimeException("Pod Execution Failed", e);
//        }
//    }
//
//    Pod findActivePod(Long projectId) {
//        return client.pods().inNamespace(NAMESPACE)
//                .withLabel(PROJECT_LABEL, projectId.toString())
//                .withLabel(POOL_LABEL, BUSY)
//                .list().getItems().stream()
//                .filter(pod -> "Running".equals(pod.getStatus().getPhase()))
//                .findFirst()
//                .orElse(null);
//    }
//}

package com.codingshuttle.projects.lovable_clone.service.impl;

import com.codingshuttle.projects.lovable_clone.dto.deploy.DeployResponse;
import com.codingshuttle.projects.lovable_clone.service.DeploymentService;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class KubernetesDeploymentServiceImpl implements DeploymentService {

    private final KubernetesClient client;
    private final StringRedisTemplate redisTemplate;

    private static final String NAMESPACE = "shuttle-apps";
    private static final String POOL_LABEL = "status";
    private static final String PROJECT_LABEL = "project-id";
    private static final String IDLE = "idle";
    private static final String BUSY = "busy";
    private static final String SYNCER_CONTAINER = "syncer";
    private static final String RUNNER_CONTAINER = "runner";
    private static final String REVERSE_PROXY_PORT = "8090";
    private static final int BACKGROUND_CMD_WAIT_MS = 15000;

    @Override
    public DeployResponse deploy(Long projectId) {

        String domain = "project-" + projectId + ".app.domain.com";
        log.info("🚀 Deploy request received for projectId={}, domain={}", projectId, domain);

        Pod existingPod = findActivePod(projectId);

        if (existingPod != null) {
            String podName = existingPod.getMetadata().getName();
            log.info("♻️ Reusing existing pod {} for project {}", podName, projectId);

            String watchCmd = String.format(
                    "nohup mc mirror --overwrite --watch myminio/projects/%d/ /app/ > /app/sync.log 2>&1 &",
                    projectId);
            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", watchCmd);

            String startCmd = "npm install && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &";
            execCommand(podName, RUNNER_CONTAINER, "sh", "-c", startCmd);

            Pod freshPod = client.pods()
                    .inNamespace(NAMESPACE)
                    .withName(podName)
                    .get();

            registerRoute(domain, freshPod);
            return new DeployResponse("http://" + domain + ":" + REVERSE_PROXY_PORT);
        }

        log.info("🆕 No active pod found. Claiming new pod...");
        return claimAndStartNewPod(projectId, domain);
    }

    private synchronized DeployResponse claimAndStartNewPod(Long projectId, String domain) {

        log.info("🔍 Searching for idle pods...");

        Pod pod = client.pods().inNamespace(NAMESPACE)
                .withLabel(POOL_LABEL, IDLE)
                .list().getItems().stream()
                .filter(p -> "Running".equals(p.getStatus().getPhase()))
                .filter(p -> p.getStatus().getConditions().stream()
                        .anyMatch(c -> "Ready".equals(c.getType())
                                && "True".equals(c.getStatus())))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("❌ No idle runners available"));

        String podName = pod.getMetadata().getName();
        log.info("✅ Found idle pod: {}. Assigning to project {}", podName, projectId);

        // Immediately mark as busy to prevent race condition
        client.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
            p.getMetadata().getLabels().put(POOL_LABEL, BUSY);
            p.getMetadata().getLabels().put(PROJECT_LABEL, projectId.toString());
            return p;
        });

        try {
            log.info("📦 Starting sync + dev server for pod {}", podName);

            // Step 1: Initial full sync from MinIO
            String initialSyncCmd = String.format(
                    "mc mirror --overwrite myminio/projects/%d/ /app/",
                    projectId);
            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", initialSyncCmd);

            // Step 2: Start watch sync (background)
            String watchCmd = String.format(
                    "nohup mc mirror --overwrite --watch myminio/projects/%d/ /app/ > /app/sync.log 2>&1 &",
                    projectId);
            execCommand(podName, SYNCER_CONTAINER, "sh", "-c", watchCmd);

            // Step 3: Install deps and start Vite (background)
            String startCmd = "npm install --legacy-peer-deps && nohup npm run dev -- --host 0.0.0.0 --port 5173 > /app/dev.log 2>&1 &";
            execCommand(podName, RUNNER_CONTAINER, "sh", "-c", startCmd);

            // Step 4: Fetch updated pod for correct IP
            Pod updatedPod = client.pods()
                    .inNamespace(NAMESPACE)
                    .withName(podName)
                    .get();

            String podIp = updatedPod.getStatus().getPodIP();
            log.info("🌐 Pod IP fetched: {}", podIp);

            // Step 5: Register route in Redis
            registerRoute(domain, updatedPod);

            log.info("✅ Deployment successful → http://{}:{}", domain, REVERSE_PROXY_PORT);
            return new DeployResponse("http://" + domain + ":" + REVERSE_PROXY_PORT);

        } catch (Exception e) {
            log.error("❌ Deployment failed for project {}. Releasing pod {}", projectId, podName, e);

            try {
                client.pods().inNamespace(NAMESPACE).withName(podName).edit(p -> {
                    p.getMetadata().getLabels().put(POOL_LABEL, IDLE);
                    p.getMetadata().getLabels().remove(PROJECT_LABEL);
                    return p;
                });
                log.info("♻️ Pod {} released back to idle pool", podName);
            } catch (Exception releaseEx) {
                log.error("❌ Failed to release pod {} back to idle pool, deleting it", podName, releaseEx);
                client.pods().inNamespace(NAMESPACE).withName(podName).delete();
            }

            throw new RuntimeException("Failed to deploy project " + projectId);
        }
    }

    private void registerRoute(String domain, Pod pod) {

        String podIp = pod.getStatus().getPodIP();
        log.info("📡 Registering route: domain={} -> podIp={}", domain, podIp);

        if (podIp == null) {
            log.error("❌ Pod IP is NULL for domain={}", domain);
            throw new RuntimeException("Pod has no IP!");
        }

        try {
            redisTemplate.opsForValue().set(
                    "route:" + domain,
                    podIp + ":5173",
                    6,
                    TimeUnit.HOURS
            );
            log.info("✅ Route stored in Redis: route:{} -> {}", domain, podIp + ":5173");
        } catch (Exception e) {
            log.error("❌ Redis write FAILED for domain={}", domain, e);
            throw e;
        }
    }

    private void execCommand(String podName, String container, String... command) {

        log.info("⚙️ Exec in pod={} container={} cmd={}",
                podName, container, String.join(" ", command));

        CompletableFuture<String> data = new CompletableFuture<>();

        try (ExecWatch ignored = client.pods().inNamespace(NAMESPACE).withName(podName)
                .inContainer(container)
                .writingOutput(new ByteArrayOutputStream())
                .writingError(new ByteArrayOutputStream())
                .usingListener(new ExecListener() {
                    @Override
                    public void onClose(int code, String reason) {
                        log.info("✅ Exec completed with code={} reason={}", code, reason);
                        data.complete("Done");
                    }
                })
                .exec(command)) {

            boolean isBackgroundCmd = command[command.length - 1].trim().endsWith("&");

            if (isBackgroundCmd) {
                log.info("⏳ Background command, waiting {}ms...", BACKGROUND_CMD_WAIT_MS);
                Thread.sleep(BACKGROUND_CMD_WAIT_MS);
            } else {
                data.get(60, TimeUnit.SECONDS);
            }

        } catch (Exception e) {
            log.error("❌ Exec failed in pod={} container={}", podName, container, e);
            throw new RuntimeException("Pod Execution Failed", e);
        }
    }

    Pod findActivePod(Long projectId) {
        return client.pods().inNamespace(NAMESPACE)
                .withLabel(PROJECT_LABEL, projectId.toString())
                .withLabel(POOL_LABEL, BUSY)
                .list().getItems().stream()
                .filter(pod -> "Running".equals(pod.getStatus().getPhase()))
                .findFirst()
                .orElse(null);
    }
}