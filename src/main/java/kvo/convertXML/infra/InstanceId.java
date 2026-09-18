package kvo.convertXML.infra;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.UUID;

/** Уникальный идентификатор запущенной копии сервиса: host/pid/uuid8 */
@Component
public class InstanceId {

    private final String value;

    public InstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
        this.value = host + "/" + pid + "/" + UUID.randomUUID().toString().substring(0, 8);
    }

    public String get() {
        return value;
    }
}