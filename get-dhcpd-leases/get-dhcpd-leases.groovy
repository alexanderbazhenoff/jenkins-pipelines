#!/usr/bin/env groovy


/**
 * Show dhcp leases info from isc-dhcp-sever, or a tiny pipeline-wrapper for python script:
 * https://github.com/alexanderbazhenoff/various-scripts/tree/master/network/get_dhcpd_leases
 *
 * This Source Code Form is subject to the terms of the Apache License Version 2.0.
 * If a copy of this source file was not distributed with this file, You can obtain one at:
 * https://github.com/alexanderbazhenoff/jenkins-shared-library/blob/main/LICENSE
 */


// Start jenkins node where git clone (and optional all required keys for ssh cloning)
def StartNode = 'master' as String
// Jenkins node with isc-dhcp-server to execute on
def ExecutionNode = 'dhcpd-server.domain' as String
// Repo URL of 'get_dhcpd_leases.py' script, e.g: 'git@github.com:alexanderbazhenoff/various-scripts.git'
def GitProjectUrlOfTheScript = 'https://github.com/alexanderbazhenoff/various-scripts.git' as String
// Full path inside the repo to run 'get_dhcpd_leases.py' script
def ScriptPathInsideTheRepo = 'network/get_dhcpd_leases/get_dhcpd_leases.py' as String
// Repo branch
def GitScriptBranch = 'master' as String
// Credentials to access repo (for ssh cloning), e.g. 'a222b01a-230b-1234-1a12345678b9'
def GitCredentials = '' as String


node(StartNode) {
    git(branch: GitScriptBranch, credentialsId: GitCredentials, url: GitProjectUrlOfTheScript)
    stash name: 'script', includes: ScriptPathInsideTheRepo
    node(ExecutionNode) {
        unstash 'script'
        sh String.format('python3 ./%s', ScriptPathInsideTheRepo)
    }
}
