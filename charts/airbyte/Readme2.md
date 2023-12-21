# Airbyte Installation OpenShift

## STEP 1 - PREPARE ACCOUNT

1. Create a Service account with necessary role and rolebinding using the serviceAccount YAML in the Airbyte Chart template folder e.g.




```
apiVersion: v1
kind: ServiceAccount
metadata:
  name: airbyte-admin

---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: airbyte-admin-role
rules:
  - apiGroups: [""]
    resources: ["jobs", "pods", "pods/log", "pods/exec", "pods/attach"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"] # over-permission for now
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: airbyte-admin-binding
roleRef:
  apiGroup: ""
  kind: Role
  name: airbyte-admin-role
subjects:
  - kind: ServiceAccount
    name: airbyte-admin

```


2.  Give project permission to the service account

` ``oc policy add-role-to-user edit system:serviceaccount:{YOUR_NAMESPACE}:airbyte-admin -n {YOUR_NAMESPACE}```
3. The service account will create a token called airbyte-admin-token-XXX, use this token to login and install airbyte
```oc login --token={token_key}```
**You do not need steps 1 when using insternal pipeline like ArgoCD**

## STEP 2 - Helm Values

1. All helm values will be set appropriately to avoid pod permission issue like enabling containerSecurityContext with right RunASUser
2. Use Volume Maps for any pod r/w permission. for e.g 

```
  containerSecurityContext:
    runAsNonRoot: true
    runAsUser: 1001110000
    readOnlyRootFilesystem: true
  ## containerSecurityContext: {}

  ##  temporal.extraInitContainers Additional InitContainers to initialize the pod es: [1001110000, 1001119999], 
  ## Examples (when using `temporal.containerSecurityContext.readOnlyRootFilesystem=true`):
  extraInitContainers:
    - name: config-loader
      image: temporalio/auto-setup:1.20.1
      command:
        - /bin/sh
        - -c
        - >-
          find /etc/temporal/config/ -maxdepth 1 -mindepth 1 -exec cp -ar {} /config/ \;
      volumeMounts:
        - name: config
          mountPath: /config
```
3.  All service should run as ClusterIP not NodePort
4.  Any service binded to port 80 should be reconfigured to another port because Port 80 is not an availble port in our openshift cluster. two service in mind is the airbyte-connector-builder-server and airbyte-webapp 
5.  The airbyte-connector-builder-server is written on Micronaut and we can deliberately reconfigure this server to use another port by passing in an environmental variable `**MICRONAUT_SERVER_PORT**`
6. To do this pass an extra env_vars in the value airbyte value chart as below

``` env_vars: 
    MICRONAUT_SERVER_PORT: "8080"
```
7. The other service is the airbyte-webapp which is a react web app proxied via an Ngnix server. The only way to change the binded port of the server is to rebuild it docker image.
8.  navigate to the airbyte-webapp source code
9. Update the package.json file and remove link obj `  "prepare": "link: scripts/install-githooks.sh",` -> `  "prepare": "./scripts/install-githooks.sh",`
9.  Update the ngnix service block on default.conf.template file to look like 
```
server {
    listen       8080;
    listen  [::]:8080;
    server_name  localhost *.gov.bc.ca;
```
10. Update build and release dockerfile
- Build:dockerfile update project dir to 
```ARG PROJECT_DIR=/workspace/airbyte-webapp```
11. build the docker image with tag for example 
``` 
docker build -t airbyte-webapp:0.50.11 -f ./airbyte-webapp/build:dockerfile . --no-cache
```
12. update the release:dockerfile to expose port 8080 and build image to you just build image i.e 
``` ARG BUILD_IMAGE=airbyte-webapp:0.50.11```
13.  Before you build the image do the following to build your node_module
     ```
     - npm install -g pnpm@8.6.12
     - nvm install 18.15.0
     - nvm use 18.15.0
     - npm i
     - pnpm i
	```
- Build the release docker build -t airbyte-webapp-release:0.50.11 -f ./airbyte-webapp/release:dockerfile . --no-cache
- Login to openshift -> 
```
docker login -u $(oc whoami) -p $(oc whoami -t) image-registry.apps.emerald.devops.gov.bc.ca
```
- Tag the release image ->
``` docker tag airbyte-webapp-release:0.50.11 image-registry.apps.emerald.devops.gov.bc.ca/{YOUR_NAMESPACE}/airbyte-webapp-release:0.50.11 ```
- Update the airbyte-webapp value image to point to this image 
- After all these you can run helm install -> for e.g 
```
{dir}\airbyte-platform\charts\airbyte> helm install my-airbyte-release . --dependency-update --values .\values.yaml

```

