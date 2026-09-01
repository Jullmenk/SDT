@echo off
cd /d "%~dp0"

set REPO=%USERPROFILE%\.m2\repository

set CP=target\classes;^
%REPO%\com\github\ipfs\java-ipfs-http-client\v1.4.4\java-ipfs-http-client-v1.4.4.jar;^
%REPO%\com\github\multiformats\java-multiaddr\v1.4.12\java-multiaddr-v1.4.12.jar;^
%REPO%\com\github\ipld\java-cid\v1.3.7\java-cid-v1.3.7.jar;^
%REPO%\com\github\multiformats\java-multihash\v1.3.4\java-multihash-v1.3.4.jar;^
%REPO%\com\github\multiformats\java-multibase\v1.1.1\java-multibase-v1.1.1.jar;^
%REPO%\com\sparkjava\spark-core\2.9.4\spark-core-2.9.4.jar;^
%REPO%\org\slf4j\slf4j-api\1.7.25\slf4j-api-1.7.25.jar;^
%REPO%\org\eclipse\jetty\websocket\websocket-server\9.4.48.v20220622\websocket-server-9.4.48.v20220622.jar;^
%REPO%\org\eclipse\jetty\websocket\websocket-common\9.4.48.v20220622\websocket-common-9.4.48.v20220622.jar;^
%REPO%\org\eclipse\jetty\websocket\websocket-client\9.4.48.v20220622\websocket-client-9.4.48.v20220622.jar;^
%REPO%\org\eclipse\jetty\jetty-client\9.4.48.v20220622\jetty-client-9.4.48.v20220622.jar;^
%REPO%\org\eclipse\jetty\websocket\websocket-servlet\9.4.48.v20220622\websocket-servlet-9.4.48.v20220622.jar;^
%REPO%\org\eclipse\jetty\websocket\websocket-api\9.4.48.v20220622\websocket-api-9.4.48.v20220622.jar;^
%REPO%\com\google\code\gson\gson\2.10.1\gson-2.10.1.jar;^
%REPO%\org\slf4j\slf4j-simple\2.0.9\slf4j-simple-2.0.9.jar;^
%REPO%\javax\servlet\javax.servlet-api\4.0.1\javax.servlet-api-4.0.1.jar;^
%REPO%\org\eclipse\jetty\jetty-server\9.4.52.v20230823\jetty-server-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-http\9.4.52.v20230823\jetty-http-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-util\9.4.52.v20230823\jetty-util-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-io\9.4.52.v20230823\jetty-io-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-webapp\9.4.52.v20230823\jetty-webapp-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-xml\9.4.52.v20230823\jetty-xml-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-servlet\9.4.52.v20230823\jetty-servlet-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-security\9.4.52.v20230823\jetty-security-9.4.52.v20230823.jar;^
%REPO%\org\eclipse\jetty\jetty-util-ajax\9.4.52.v20230823\jetty-util-ajax-9.4.52.v20230823.jar

echo A arrancar Lider com classpath=%CP%
java -cp "%CP%" lider.Lider
