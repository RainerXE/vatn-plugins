# VATN Plugins

Drop-in plugins for the [VATN runtime](https://github.com/RainerXE/vatn).

## Available Plugins

| Plugin | Artifact | What it does |
|--------|----------|--------------|
| [vatn-plugin-auth](vatn-plugin-auth/) | `dev.vatn.plugins:vatn-plugin-auth` | JWT authentication — login, refresh, and bearer token validation via three HTTP endpoints |

## Adding a Plugin

1. Install the VATN runtime to your local Maven repo first:
   ```sh
   cd /path/to/vatn && mvn install -DskipTests
   ```

2. Add the plugin dependency to your project:
   ```xml
   <dependency>
       <groupId>dev.vatn.plugins</groupId>
       <artifactId>vatn-plugin-auth</artifactId>
       <version>1.0-SNAPSHOT</version>
   </dependency>
   ```

3. Register the plugin with the node runner:
   ```java
   runner.addPlugin(new AuthPlugin(AuthConfig.of(secret, validator)));
   ```

## Building

```sh
# Requires vatn-api to be installed locally first
mvn install
```
