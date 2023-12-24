
## Airbyte Installation

Prior to deploying Airbyte on our OpenShift namespace, several prerequisite configurations must be established. Currently, the airbyte-webapp is bound to run on port *:80, a port that is presently unavailable for use within our OpenShift Cluster. To address this, follow the steps outlined below.

Before initiating the installation, ensure the following prerequisites are installed:

- Java 21 SRE SDK
- Docker CLI
- Docker

1. Clone the Airbyte platform repository:

```
git clone https://github.com/sookeke/airbyte-platform.git
```
2. Checkout the release branch of interest (for stable version v0.50.35):

```
cd airbyte-platform
git checkout 0.50.35
```
3. On Windows, two crucial settings must be configured. Firstly, enforce LF for Git:

```
git config --global core.autocrlf input
git rm --cached -r .
git reset --hard
```
4. Update all the package.json execution scripts to use Bash instead of the Linux shell:

```
"prepare": "bash ./scripts/install-githooks.sh"
```
5. Change the airbyte-webapp application port from 80 to 8080 in two files:

	1. Nginx server block file (airbyte-webapp/nginx/default.conf.template)
	2. Dockerfile (Update all Dockerfiles in the webapp folder to expose port 8080 instead of 80)
6. Build the webapp using the following command from the project root:

```
./gradlew :airbyte-webapp:assemble
```
***This build will generate a Docker image with the release version as the image tag (e.g., airbyte/webapp:0.50.35). Publish this image to your desired image registry.***
7. Update the webapp.image.repository to point to this registry in the value.yaml file

### Airbyte Helm Install and Upgrade:

See the minimum required [values](https://github.com/sookeke/airbyte-platform/blob/0.50.35-oc/charts/airbyte/values.yaml) for Airbyte Helm installation on OpenShift, considering customized these values to suite your installations on OpenShift such as Gold or Emerald.

Note: Utilize Minio S3 for logs, sessions, and state. Ensure that the Minio object storage is configured appropriately with considerations for permissions and settings, including MC_CONFIG_DIR. Copy or update your minio.yaml to reflect the one in the [repository](https://github.com/sookeke/airbyte-platform/blob/0.50.35-oc/charts/airbyte/templates/minio.yaml).

Afterward, navigate to the charts/airbyte directory and run the following Helm command with your value file:

```
helm upgrade csbiss . --dependency-update --values ./values.yaml
```

#### Securing Airbyte:

As Airbyte connectors serve as data pipes moving data from Point A to Point B, it is advised not to expose Airbyte publicly without robust authentication through a valid Identity Provider. For securing Airbyte, use OAuth2-proxy with our DIAM SSO to secure the Airbyte UI. Refer to the airbyte-secure folder for bitnami/oauth2-proxy values for installation and configuration.

Before running the installation, coordinate with the DIAM team for the OIDC client secret. Finally, install the airbyte-proxy using the following Helm command:

```
helm install airbyte-proxy oci://registry-1.docker.io/bitnamicharts/oauth2-proxy --values values.yaml
```