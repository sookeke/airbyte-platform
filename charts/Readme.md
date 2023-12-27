
## Airbyte Installation


git clone the latest stable version 

git clone -b 0.50.38 https://github.com/sookeke/airbyte-platform.git


Before deploying Airbyte in our OpenShift namespace, we must establish several prerequisite configurations. Specifically, we need to create the airbyte-admin service account along with its corresponding roles and role bindings. Utilize the provided service account YAML in the Airbyte chart template, and subsequently, set the variable "createServiceAccount" to false.

Additionally, the airbyte-webapp is currently configured to run on port *:80, a port that is presently unavailable within our OpenShift Cluster. To address this issue, follow the steps outlined below.

To resolve the port conflict, rebuild the container Docker image and copy our NGINX server configuration into the webapp NGINX configuration location to overwrite it. Don't forget to increase the proxy timeout if you encounter NGINX 503 server timeout issues. Refer to the default server configuration in the webapp chart directory for guidance.
```
FROM airbyte/webapp:0.50.38


EXPOSE 8080

COPY default.conf.template /etc/nginx/templates/default.conf.template

```

Another Issue we need is the build.log permission issue with the airbyte connector builder.
rebuild the image as before and set neccessarilt previlege on the file a sbelow

```
FROM airbyte/connector-builder-server:0.50.38 AS Base

WORKDIR /app
USER root

RUN touch build.log && chmod 666 build.log

USER 1000

```



### Airbyte Helm Install and Upgrade:

1. Build, tag and publish the above images to your preferefered container registry or aritifactory. Note the tag has to be the airbyte release version i.e. git branch
2. Set the image repository value for these service in the value file to point to this repository
3. See other minimum required [values](https://github.com/sookeke/airbyte-platform/blob/0.50.38/charts/airbyte/values.yaml) for Airbyte Helm installation on OpenShift, considering customized these values to suite your installations on OpenShift such as Gold or Emerald.

Note: Utilize Minio S3 for logs, sessions, and state. Ensure that the Minio object storage is configured appropriately with considerations for permissions and settings, including MC_CONFIG_DIR. Copy or update your minio.yaml to reflect the one in the [repository](https://github.com/sookeke/airbyte-platform/blob/0.50.35-oc/charts/airbyte/templates/minio.yaml).

Afterward, navigate to the charts/airbyte directory and run the following Helm command with your value file:

```
helm upgrade csbiss . --dependency-update --values ./values.yaml
```

#### Securing Airbyte:

As Airbyte connectors serve as data pipes moving data from Point A to Point B, it is advised not to expose Airbyte publicly without robust authentication through a valid Identity Provider. For securing Airbyte, use OAuth2-proxy with our DIAM SSO to secure the Airbyte UI. Refer to the airbyte-secure folder for [bitnami/oauth2-proxy values](https://github.com/sookeke/airbyte-platform/blob/0.50.35-oc/charts/airbyte-secure/values.yaml) for installation and configuration.

Before running the installation, coordinate with the DIAM team for the OIDC client secret. Finally, install the airbyte-proxy using the following Helm command using airbyte-secure dir as root:

```
helm install airbyte-proxy oci://registry-1.docker.io/bitnamicharts/oauth2-proxy --values values.yaml
```

### Scaling Airbyte in OpenShift

Airbyte jobs demand significant computational resources, necessitating a minimum of 8 free CPUs, but ideally, 16 CPUs within the OpenShift cluster. It is crucial to consider the CPU configurations for both sidecars and jobs. Each job pod initiated by Airbyte consists of 5 total containers, including sidecars, emphasizing the need to be mindful of the overall CPU utilization for a single job. To manage utilization effectively, utilize CPU REQUEST and LIMIT parameters.

Additionally, Airbyte API calls follow an asynchronous pattern, passing through a message channel via Temporal before execution. When a server API call is initiated through the NGINX proxy, the event is intercepted by the Temporal service and queued for the targeted worker. This targeted worker creates a job associated with the request, involving 5 containers to invoke the backend API. Upon successful execution, the response is sent back to the client via the Temporal service.

It is important to note that potential NGINX timeout issues may arise if the container responsible for handling requests fails to respond promptly. Ensure proper allocation of CPU and memory resources for job and sidecar containers using environment variables.

```

jobs:
  resources:
    requests:
      memory: 256Mi
      cpu: 100m
    limits:
      cpu: 500m
      memory: 1Gi


```

And for worker configuration:

```

extraEnv:
  - name: SOCAT_KUBE_CPU_REQUEST
    value: 0.5m
  - name: SOCAT_KUBE_CPU_LIMIT
    value: 500m 
  - name: SIDECAR_KUBE_CPU_LIMIT
    value: 500m
  - name: SIDECAR_KUBE_CPU_REQUEST
    value: 0.5m


```

Before a sync job runs (source connector job + destination connector job = 2 Jobs = 10 containers), optimal performance and avoidance of 503 timeouts necessitate that each job uses a CPU of at least 3/3.5 for all 5 containers. This implies a minimum of 7 free CPUs for usage when running a sync job concurrently.