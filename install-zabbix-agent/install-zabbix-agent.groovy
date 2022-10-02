#!/usr/bin/env groovy

/**
 * A jenkins pipeline for installing and customizing zabbix agent, or a wrapper for
 * alexanderbazhenoff.linux.zabbix_agent ansible role:
 * https://github.com/alexanderbazhenoff/ansible-collection-linux/tree/main/roles/zabbix_agent
 * https://galaxy.ansible.com/alexanderbazhenoff/linux
 *
 * Requires:
 * - AnsiColor Jenkins plugin: https://plugins.jenkins.io/ansicolor/
 * - Ansible Jenkins plugin: https://plugins.jenkins.io/ansible/
 *
 * MIT No Attribution (MIT-0)
 * LICENSE
 *
 * Copyright 2021, Alexander Bazhenov.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software,
 * and to permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
 * CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */

import org.codehaus.groovy.runtime.StackTraceUtils
import groovy.text.StreamingTemplateEngine
import hudson.model.Result


// Pipeline parameters defaults
def AnsibleGitRepoUrl = 'https://github.com/alexanderbazhenoff/ansible-collection-linux.git' as String
def AnsibleGitDefaultBranch = 'main' as String
def AnsibleGitCredentialsId = '' as String                           // If you wish to clone from non-public repo
def AnsibleInstallationName = 'home_local_bin_ansible' as String     // Set your ansible installation name
def NodesToExecute = ['domain.com'] as ArrayList
def ZabbixAgentVersions = ['5.0', '4.0'] as ArrayList


// Playbook template, inventory files and ansible repo path
def AnsibleDefaultPlaybookTemplate = '''\
---
- hosts: all
  become: true
  become_method: sudo
  tasks:
    - name: Include zabbix_agent role
      ansible.builtin.include_role:
        name: emzior.services.zabbix_agent
      vars:
        agent_version: $agent_version
        install_v2_agent: $install_v2_agent
        customize_agent: $network_bridge_name
        customize_agent_only: $customize_agent_only
        clean_install: $clean_install
        force_install_agent_v1: $force_install_agent_v1       
''' as String
def AnsibleServersPassivePlaybookTemplate = '''\
  zabbix_servers_passive: $servers_passive
''' as String
def AnsibleServersActivePlaybookTemplate = '''\
  zabbix_servers_passive: $servers_active
''' as String
def AnsibleInventoryTemplate = '''\
[all]
$hosts_list
[all:vars]
ansible_connection=ssh
ansible_become_user=root
ansible_ssh_common_args='-o StrictHostKeyChecking=no\'
ansible_ssh_user=$ssh_user
ansible_ssh_pass=$ssh_password
ansible_become_pass=$ssh_become_password
''' as String


/**
 * More readable exceptions with line numbers.
 *
 * @param error - Exception error
 * @return
 */
static String readableError(Throwable error) {
    return String.format('Line %s: %s', error.stackTrace.head().lineNumber, StackTraceUtils.sanitize(error))
}

/**
 * Print event-type and message.
 *
 * @param eventnum - event type: debug, info, etc...
 * @param text - text to output
 */
def outMsg(Integer eventnum, String text) {
    wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
        List eventTypes = [
                '\033[0;34mDEBUG\033[0m',
                '\033[0;32mINFO\033[0m',
                '\033[0;33mWARNING\033[0m',
                '\033[0;31mERROR\033[0m']
        println String.format('%s | %s | %s', env.JOB_NAME, eventTypes[eventnum], text)
    }
}

/**
 * Run ansible role/plabook with (optional) ansible-galaxy collections install.
 *
 * @param ansiblePlaybookText - text content of ansible playbook/role
 * @param ansibleInventoryText - text content of ansible inventory file
 * @param ansibleGitlabUrl - git URL of ansible project to clone and run
 * @param ansibleGitlabBranch - git branch of ansible project
 * @param gitCredentialsID - git credentials ID
 * @param ansibleExtras - (optional) extra params for playbook running
 * @param ansibleCollections - (optional) list of ansible-galaxy collections dependencies which will be installed before
 *                             running the script. Collections should be placed in ansible gitlab project according to
 *                             ansible-galaxy directory layout standards. If variable wasn't pass (empty) the roles
 *                             will be called an old-way from a playbook placed in 'roles/execute.yml'. It's only for
 *                             the backward capability.
 * @param ansibleInstallation - name of the ansible installation predefined in jenkins Global Configuration Tool.
 *                              Check https://issues.jenkins.io/browse/JENKINS-67209 for details.
 * @return - success (true when ok)
 */
Boolean runAnsible(String ansiblePlaybookText, String ansibleInventoryText, String ansibleGitlabUrl,
                   String ansibleGitlabBranch, String gitCredentialsID, String ansibleExtras = '',
                   String ansibleInstallation = '') {
    try {
        dir('ansible') {
            sh 'sudo rm -rf *'
            git(branch: ansibleGitlabBranch,
                    credentialsId: gitCredentialsID,
                    url: ansibleGitlabUrl)
            if (sh(returnStdout: true, returnStatus: true, script: '''ansible-galaxy collection build 
                        ansible-galaxy collection install $(ls -1 | grep ".tar.gz") -f''') != 0) {
                outMsg(3, 'There was an error building and installing ansible collection.')
                sh 'exit 1'
            }
            writeFile file: 'inventory.ini', text: ansibleInventoryText
            writeFile file: 'execute.yml', text: ansiblePlaybookText
            outMsg(1, String.format('Running from:\n%s\n%s', ansiblePlaybookText, ("-" * 32)))
            ansiblePlaybook(
                    playbook: 'execute.yml',
                    inventory: 'inventory.ini',
                    colorized: true,
                    extras: ansibleExtras,
                    installation: ansibleInstallation)
        }
        return true
    } catch (Exception err) {
        outMsg(3, String.format('Running ansible failed: %s', readableError(err)))
        return false
    }
}


node(env.JENKINS_NODE) {
    wrap([$class: 'TimestamperBuildWrapper']) {

        // Pipeline parameters check and handling
        Map envVars = [:]
        Boolean pipelineVariableNotDefined
        env.getEnvironment().each { name, value -> envVars.put(name, value) }
        ArrayList requiredVariablesList = ['IP_LIST',
                                           'SSH_LOGIN',
                                           'SSH_PASSWORD',
                                           'ZABBIX_AGENT_VERSION',
                                           'ANSIBLE_GIT_URL',
                                           'ANSIBLE_GIT_BRANCH']
        ArrayList otherVariablesList = ['SSH_SUDO_PASSWORD',
                                        'INSTALL_AGENT_V2',
                                        'CUSTOMIZE_AGENT',
                                        'CUSTOMIZE_AGENT_ONLY',
                                        'CLEAN_INSTALL',
                                        'CUSTOM_PASSIVE_SERVERS_IPS',
                                        'CUSTOM_ACTIVE_SERVERS_IPS',
                                        'JENKINS_NODE',
                                        'DEBUG_MODE']
        (requiredVariablesList + otherVariablesList).each {
            if (!envVars.containsKey(it))
                pipelineVariableNotDefined = true
        }
        if (pipelineVariableNotDefined) {
            properties([
                    parameters(
                            [string(name: 'IP_LIST',
                                    description: 'Space separated IP or DNS list.',
                                    trim: true),
                             string(name: 'SSH_LOGIN',
                                     description: 'Login for SSH connection (The same for all hosts).',
                                     trim: true),
                             password(name: 'SSH_PASSWORD',
                                     description: 'SSH password (The same for all hosts).'),
                             password(name: 'SSH_SUDO_PASSWORD',
                                     description: String.format('%s<br>%s<br><br><br>',
                                             'SSH sudo password or root password (The same for all hosts).',
                                             'If this parameter is empy SSH_PASSWORD will be used.')),
                             booleanParam(name: 'INSTALL_AGENT_V2',
                                     description: 'Install Zabbix agent v2 when possible.',
                                     defaultValue: true),
                             booleanParam(name: 'CUSTOMIZE_AGENT',
                                     description: 'Configure Zabbix agent config for service discovery.',
                                     defaultValue: true),
                             booleanParam(name: 'CUSTOMIZE_AGENT_ONLY',
                                     description: 'Configure Zabbix agent config for service discovery without install.',
                                     defaultValue: false),
                             choice(name: 'ZABBIX_AGENT_VERSION',
                                     description: 'Zabbix agent version.',
                                     choices: ZabbixAgentVersions),
                             booleanParam(name: 'CLEAN_INSTALL',
                                     description: 'Remove old versions of Zabbix agent with configs first.<br><br><br>',
                                     defaultValue: true),
                             string(name: 'CUSTOM_PASSIVE_SERVERS_IPS',
                                     description: String.format('%s<br>%s %s',
                                             'Custom Zabbix Servers Passive IP(s).',
                                             'Split this by comma for several IPs. Leave this field blank for default',
                                             'Zabbix Servers IPs.'),
                                     trim: true),
                             string(name: 'CUSTOM_ACTIVE_SERVERS_IPS',
                                     description: String.format('%s<br>%s %s<br><br><br><br><br>',
                                             'Custom Zabbix Servers Active IP(s) and port(s), e.g.: A.B.C.D:port',
                                             'Split this by comma for several IPs. Leave this field blank for default',
                                             'IPs.'),
                                     trim: true),
                             string(name: 'ANSIBLE_GIT_URL',
                                     description: 'Gitlab URL of ansible project with install_zabbix role.',
                                     defaultValue: AnsibleGitRepoUrl,
                                     trim: true),
                             string(name: 'ANSIBLE_GIT_BRANCH',
                                     description: 'Gitlab branch of ansible project with install_zabbix role.',
                                     defaultValue: AnsibleGitDefaultBranch,
                                     trim: true),
                             choice(name: 'JENKINS_NODE',
                                     description: 'List of possible jenkins nodes to execute.',
                                     choices: NodesToExecute),
                             booleanParam(name: 'DEBUG_MODE', defaultValue: false)]
                    )
            ])
            outMsg(1,
                    "Pipeline parameters was successfully injected. Select 'Build with parameters' and run again.")
            currentBuild.build().getExecutor().interrupt(Result.SUCCESS)
            sleep(time: 3, unit: "SECONDS")
        }
        Boolean errorsFound = false
        ArrayList requiredVariablesValueList = [env.IP_LIST,
                                                env.SSH_LOGIN,
                                                env.SSH_PASSWORD,
                                                env.ZABBIX_AGENT_VERSION,
                                                env.ANSIBLE_GIT_URL,
                                                env.ANSIBLE_GIT_BRANCH]
        for (int i = 0; i < requiredVariablesList.size(); i++) {
            if (!requiredVariablesValueList[i]?.trim()) {
                errorsFound = true
                outMsg(3, String.format("%s is undefined for current job run", requiredVariablesList[i]))
            }
        }
        if (errorsFound)
            error 'Missing pipeline parameters.'

        if (!env.SSH_SUDO_PASSWORD?.trim()) {
            outMsg(2, 'SSH_SUDO_PASSWORD wasn\'t set, will be taken from SSH_PASSWORD.')
            env.SSH_SUDO_PASSWORD = env.SSH_PASSWORD
        }
        Map ansiblePlaybookVariableBinding = [
                install_v2_agent      : env.INSTALL_AGENT_V2,
                network_bridge_name   : env.CUSTOMIZE_AGENT,
                customize_agent_only  : env.CUSTOMIZE_AGENT_ONLY,
                clean_install         : env.CLEAN_INSTALL,
                force_install_agent_v1: !(env.INSTALL_AGENT_V2.toBoolean()).toString(),
                agent_version         : env.ZABBIX_AGENT_VERSION
        ]
        if (env.CUSTOM_PASSIVE_SERVERS_IPS?.trim()) {
            println String.format('Found custom active zabbix server(s): %s', env.CUSTOM_PASSIVE_SERVERS_IPS)
            AnsibleDefaultPlaybookTemplate += AnsibleServersPassivePlaybookTemplate
            ansiblePlaybookVariableBinding += [servers_passive: env.CUSTOM_PASSIVE_SERVERS_IPS]
        }
        if (env.CUSTOM_ACTIVE_SERVERS_IPS?.trim()) {
            println String.format('Found custom passive zabbix server(s): ', env.CUSTOM_PASSIVE_SERVERS_IPS)
            AnsibleDefaultPlaybookTemplate += AnsibleServersActivePlaybookTemplate
            ansiblePlaybookVariableBinding += [servers_active: env.CUSTOM_ACTIVE_SERVERS_IPS]
        }
        String ansiblePlaybookText = new StreamingTemplateEngine().createTemplate(AnsibleDefaultPlaybookTemplate)
                .make(ansiblePlaybookVariableBinding).toString()
        Map ansibleInventoryVariableBinding = [
                hosts_list         : env.IP_LIST.replaceAll(' ', '\n'),
                ssh_user           : env.SSH_LOGIN,
                ssh_password       : env.SSH_PASSWORD,
                ssh_become_password: env.SSH_SUDO_PASSWORD
        ]
        String ansibleInventoryText = new StreamingTemplateEngine().createTemplate(AnsibleInventoryTemplate)
                .make(ansibleInventoryVariableBinding).toString()

        // Clean SSH hosts fingerprints from ~/.ssh/known_hosts
        env.IP_LIST.split(' ').toList().each {
            sh String.format('ssh-keygen -f "${HOME}/.ssh/known_hosts" -R %s', it)
            String ipAddress = sh(script: String.format('getent hosts %s | cut -d\' \' -f1', it), returnStdout: true)
                    .toString()
            if (ipAddress?.trim())
                sh String.format('ssh-keygen -f "${HOME}/.ssh/known_hosts" -R %s', ipAddress)
        }

        // Run ansible role
        String ansibleVerbose = (env.DEBUG_MODE.toBoolean()) ? '-vvvv' : ''
        String ansibleRunArgs = String.format('%s %s', ansibleCheckMode, ansibleVerbose)
        wrap([$class: 'AnsiColorBuildWrapper', 'colorMapName': 'xterm']) {
            Boolean ansiblePlaybookStatus = runAnsible(ansiblePlaybookText, ansibleInventoryText, env.ANSIBLE_GIT_URL,
                    env.ANSIBLE_GIT_BRANCH, AnsibleGitCredentialsId, ansibleRunArgs, AnsibleInstallationName)
            sh 'sudo rm -f ansible/inventory.ini'
            if (!ansiblePlaybookStatus)
                sh 'exit 1'
        }
    }
}
