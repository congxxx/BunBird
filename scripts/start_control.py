"""Start the packaged Java service using .env without shell-specific environment setup."""

import os
import subprocess
from pathlib import Path

from dotenv import load_dotenv

root = Path(__file__).resolve().parents[1]
load_dotenv(root / ".env")
java_home = os.getenv("JAVA_HOME")
java = str(Path(java_home) / "bin" / "java") if java_home else "java"
jar = root / "backend" / "target" / "bunbird-control-0.1.0.jar"
if not jar.exists():
    raise SystemExit("请先使用 JDK 21+ 执行 mvn -f backend/pom.xml package")
try:
    raise SystemExit(subprocess.call([java, "-jar", str(jar)], cwd=root))
except KeyboardInterrupt:
    pass
