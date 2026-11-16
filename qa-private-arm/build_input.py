"""Keep private Range diagnostics consistent with the actual app's default UA."""
from pathlib import Path
import re

source = Path("composeApp/src/commonMain/kotlin/com/yfuse/core/network/HttpClientFactory.kt").read_text()
match = re.search(r'const val DEFAULT_EMBY_USER_AGENT: String = "([^"\r\n]+)"', source)
if not match or match[1] != "Emby for Android Mobile":
    raise SystemExit("Review the private fixture UA against the changed app default")
driver = Path("qa-source-trace/Driver.java").read_text()
assert '"Yfuse/1.9.4"' in driver
Path("qa-volume/input-driver.java").write_text(driver.replace('"Yfuse/1.9.4"', '"' + match[1] + '"'))
