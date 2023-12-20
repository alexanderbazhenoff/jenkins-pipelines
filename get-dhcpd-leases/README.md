# Get DHCPD leases jenkins pipeline

A tiny wrapper for
[python script](https://github.com/alexanderbazhenoff/various-scripts/tree/master/network/get_dhcpd_leases) to get
leases info from isc-dhcp-server using Jenkins.

## Requirements

1. Jenkins version 2.190.2 or higher (older versions are probably also fine, but wasn't tested).
2. [Linux jenkins node](https://www.jenkins.io/doc/book/installing/linux/) installed and configured on your dhcp
   server to run this pipeline.
3. python3 installed both on `StartNode` and `ExecutionNode`.
4. Download [oui.txt](https://standards-oui.ieee.org/) file to `/usr/local/etc/oui.txt` path on your isc-dhcp server. Or
download this file to your custom path then change `VENDORS_FILE_PATH` variable in the script.

## Usage

1. Create jenkins pipeline with 'Pipeline script from SCM', set-up SCM, Branch Specifier as `*/main` and Script Path as
   `get-dhcpd-leases/get-dhcpd-leases.groovy`.
2. Specify defaults for jenkins pipeline parameters in a global variables of pipeline code.
