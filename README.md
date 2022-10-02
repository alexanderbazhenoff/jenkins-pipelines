Various jenkins scripted pipelines:

- [**golang-app-docker-ci**](golang-app-docker-ci/README.md) - Jenkins Pipeline to clone golang sources of the project,
  run tests inside docker container and archive docker image with application binary as artifacts.
- [**install-zabbix-agent**](install-zabbix-agent/README.md) - A jenkins pipeline for installing and customizing zabbix
  agent, or a wrapper for 
  [zabbix_agent](https://github.com/alexanderbazhenoff/ansible-collection-linux/tree/main/roles/zabbix_agent)
  ansible role.
