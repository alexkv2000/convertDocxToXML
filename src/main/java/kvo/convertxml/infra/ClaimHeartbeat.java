package kvo.convertxml.infra;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ClaimHeartbeat {

    private final Map<Long, UUID> claims = new ConcurrentHashMap<>();
    private final DocumentTaskDao dao;
    private final InstanceId instanceId;

    public ClaimHeartbeat(DocumentTaskDao dao, InstanceId instanceId) {
        this.dao = dao;
        this.instanceId = instanceId;
    }

    public void register(long id, UUID token) { claims.put(id, token); }
    public void release(long id) { claims.remove(id); }

    /** Пока процесс жив — продлеваем locked_at захваченных (в т.ч. стоящих в heavy-очереди). */
    @Scheduled(fixedDelayString = "${app.heartbeat-interval-ms:300000}")
    public void beat() {
        if (!claims.isEmpty()) {
            dao.heartbeat(instanceId.get(), claims);
        }
    }
}
