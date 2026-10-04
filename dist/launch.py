import subprocess
import sys

# Replace with the absolute path to your JAR file
jar_path = r"objviewer.jar"

# Pass any extra command-line arguments to the Java jar
args = ["java", "-jar", jar_path] + sys.argv[1:]

# Run the command
subprocess.run(args)
