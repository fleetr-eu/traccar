FROM maven:3.5.4-jdk-8-alpine as builder

WORKDIR /build

COPY ./ ./

RUN mvn package -P jar-with-dependencies



FROM java:8-jre

RUN mkdir -p /opt/traccar/logs

WORKDIR /opt/traccar

COPY --from=builder /build/target/tracker-server-jar-with-dependencies.jar /opt/traccar/tracker-server-jar-with-dependencies.jar
ADD setup/default.xml /opt/traccar/conf/default.xml
ADD setup/fleetr/traccar.xml /opt/traccar/conf/traccar.xml
ADD schema/ /opt/traccar/schema/
ADD traccar-web/web/ /opt/traccar/web/

EXPOSE 8082

ENTRYPOINT java -jar tracker-server-jar-with-dependencies.jar conf/traccar.xml
