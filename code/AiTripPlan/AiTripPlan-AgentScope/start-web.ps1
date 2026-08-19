$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.17.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;C:\Users\29843\.maven\maven-3.9.12\bin;$env:Path"
$env:MIMO_API_KEY = [Environment]::GetEnvironmentVariable('MIMO_API_KEY', 'User')
$env:MIMO_BASE_URL = 'https://api.xiaomimimo.com/v1'
$env:MIMO_MODEL_NAME = 'mimo-v2.5'
$env:MIMO_MAX_TOKENS = [Environment]::GetEnvironmentVariable('MIMO_MAX_TOKENS', 'User')
$env:BAIDU_MAP_MCP_SSE = [Environment]::GetEnvironmentVariable('BAIDU_MAP_MCP_SSE', 'User')
$env:BAIDU_MAP_MCP_TIMEOUT_SECONDS = if ([Environment]::GetEnvironmentVariable('BAIDU_MAP_MCP_TIMEOUT_SECONDS', 'User')) { [Environment]::GetEnvironmentVariable('BAIDU_MAP_MCP_TIMEOUT_SECONDS', 'User') } else { '15' }
$env:AITRIPPLAN_DEMO_ROUTE = if ([Environment]::GetEnvironmentVariable('AITRIPPLAN_DEMO_ROUTE', 'User')) { [Environment]::GetEnvironmentVariable('AITRIPPLAN_DEMO_ROUTE', 'User') } else { 'false' }
$env:AITRIPPLAN_ROUTE_CACHE_FILE = [Environment]::GetEnvironmentVariable('AITRIPPLAN_ROUTE_CACHE_FILE', 'User')
$env:AITRIPPLAN_EXPERIMENT_MODE = ''
$env:AITRIPPLAN_EXPERIMENT_ORCHESTRATION = 'false'
$env:AITRIPPLAN_VERIFY_REMOTE = 'false'
$env:NACOS_USERNAME = [Environment]::GetEnvironmentVariable('NACOS_USERNAME', 'User')
$env:NACOS_PASSWORD = [Environment]::GetEnvironmentVariable('NACOS_PASSWORD', 'User')

Set-Location $PSScriptRoot
& 'C:\Users\29843\.maven\maven-3.9.12\bin\mvn.cmd' -f manager_agent\pom.xml -DskipTests org.springframework.boot:spring-boot-maven-plugin:4.0.2:run
