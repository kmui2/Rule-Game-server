FROM tomcat:latest

# Installs Ant
ENV ANT_VERSION 1.10.8
RUN cd && \
    wget -q http://www.us.apache.org/dist//ant/binaries/apache-ant-${ANT_VERSION}-bin.tar.gz && \
    tar -xzf apache-ant-${ANT_VERSION}-bin.tar.gz && \
    mv apache-ant-${ANT_VERSION} /opt/ant && \
    rm apache-ant-${ANT_VERSION}-bin.tar.gz
ENV ANT_HOME /opt/ant
ENV PATH ${PATH}:/opt/ant/bin

RUN mkdir /w2020
RUN mkdir /w2020/game

RUN ln -s /usr/local/tomcat /opt/tomcat


RUN mkdir /opt/tomcat/game-data
RUN mkdir /opt/tomcat/saved

COPY . /w2020/game

RUN rm -rf /w2020/game/lib

COPY lib/jaxb-ri /w2020/jaxb-ri
COPY lib/jaxrs-ri /w2020/jaxrs-ri
COPY lib/apache-openjpa-3.1.0 /opt/apache-openjpa-3.1.0
COPY lib/mysql-connector-java-8.0.21.jar /opt/tomcat/lib/mysql-connector-java-8.0.20.jar

RUN cd /w2020/game && /opt/ant/bin/ant clean javadoc

RUN cd /w2020/game && /opt/ant/bin/ant war
RUN cp /w2020/w2020.war /opt/tomcat/webapps

RUN cd /w2020/game && /opt/ant/bin/ant war-dev
RUN cp /w2020/w2020-dev.war /opt/tomcat/webapps

EXPOSE 8080

CMD ["catalina.sh", "run"]
