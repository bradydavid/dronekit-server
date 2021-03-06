echo "This script will deploy to ec2"

set -e
sbt clean assembly

export EC2_HOSTNAME=api.3dr.com

./ssh-ec2 sudo skill java

echo
echo Copying up new version
echo

# we send up src as a super skanky hack because our assembly still accidentally references
# src/main/webapp/WEB-INF
./ssh-ec2 cp apihub-assembly-\*.jar backup
rsync -avz -e "ssh -l ubuntu -i /home/kevinh/.ssh/id_dsa_dronehub" drone-mysql.sh target/scala-2.10/apihub-assembly-*.jar ubuntu@$EC2_HOSTNAME:
rsync -avz -e "ssh -l ubuntu -i /home/kevinh/.ssh/id_dsa_dronehub" src/main/webapp ubuntu@$EC2_HOSTNAME:src/main
rsync -avz -e "ssh -l ubuntu -i /home/kevinh/.ssh/id_dsa_dronehub" ardupilot/Tools/LogAnalyzer ubuntu@$EC2_HOSTNAME:

rsync -avz -e "ssh -l ubuntu -i /home/kevinh/.ssh/id_dsa_dronehub" S98nestor-startup ubuntu@$EC2_HOSTNAME:/tmp
./ssh-ec2 sudo mv /tmp/S98nestor-startup /etc/rc2.d

TAGNAME=deploy-`date +%F-%H%M%S`
echo "Tagging new deployment: $TAGNAME"
git tag -a $TAGNAME -m deployed
git push --tags

echo
echo "Starting new version...."
./ssh-ec2 /etc/rc2.d/S98nestor-startup

# Tell newrelic we just pushed a new load
# curl -H "x-api-key:apikeyfixme" -d "deployment[app_name]=My Application" -d "deployment[user]=$USER" -d "deployment[description]=deploy-to-ec2" https://api.newrelic.com/deployments.xml
