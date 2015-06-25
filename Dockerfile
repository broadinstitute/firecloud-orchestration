FROM centos:7

curl https://bintray.com/sbt/rpm/rpm > /etc/yum.repos.d/bintray-sbt-rpm.repo
yum -y install java-1.8.0-openjdk sbt 
