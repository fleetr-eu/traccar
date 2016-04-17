FROM java:8-jre

RUN mkdir -p /opt/traccar/logs

WORKDIR /opt/traccar

ADD target/tracker-server-jar-with-dependencies.jar /opt/traccar/tracker-server-jar-with-dependencies.jar
ADD setup/fleetr/traccar.xml /opt/traccar/conf/traccar.xml
ADD database/changelog-3.3.xml /opt/traccar/data/changelog-3.3.xml
ADD database/changelog-3.5.xml /opt/traccar/data/changelog-3.5.xml
ADD database/changelog-master.xml /opt/traccar/data/changelog-master.xml
ADD web/ /opt/traccar/web

EXPOSE 8082

ENTRYPOINT java -jar tracker-server-jar-with-dependencies.jar conf/traccar.xml
