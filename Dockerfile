# syntax=docker/dockerfile:1
FROM eclipse-temurin:26-jre
WORKDIR /app
COPY target/aletheia-*.jar app.jar
EXPOSE 8431

# The base image ships neither curl nor wget, and installing packages just for a probe is not
# worth the image surface -- bash's /dev/tcp does the whole HTTP/1.0 exchange natively.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8431 && \
        printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && \
        grep -q "\"status\":\"UP\"" <&3'

ENTRYPOINT ["java", "-jar", "app.jar"]
