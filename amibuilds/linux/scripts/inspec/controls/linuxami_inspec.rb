#Packeges
#ensure that amazon-ssm-agent package is installed
describe package('amazon-ssm-agent') do
  it { should be_installed }
end

#Services
#ensure that httpd is installed
describe service('httpd') do
  it { should be_installed }
  #it { should be_enabled }
  #it { should be_running }
end
#ensure that sshd is installed and enabled
describe service('sshd') do
  it { should be_installed }
  it { should be_enabled }
  #it { should be_running }
end

#Files and directories
#ensure that a file existed (example)
# describe file('/tmp/diskconfig.sh') do
#   it { should be_file }
# end

#it may be necessary to specify the service manager by using one of the following service manager-specific resources:
# bsd_service, launchd_service, runit_service, systemd_service, sysv_service, or upstart_service
#ensure that httpd is installed and enabled
# describe upstart_service('httpd') do
#   it { should be_installed }
#   it { should be_enabled }
#   #it { should be_running }
# end