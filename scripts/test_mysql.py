"""Run JDBC contract tests on a newly initialized MySQL server, never the installed service.

Requires Python 3, Maven and the MySQL server/client binaries. No third-party Python packages.
Data and diagnostic logs stay in ignored target/mysql-test-* directories for review.
"""
from pathlib import Path
import argparse
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-bin', type=Path, help='Directory containing mysqld and mysql')
    parser.add_argument('--smoke-jar', action='store_true', help='Also test the packaged application; run mvn package first')
    args = parser.parse_args()
    executable_suffix = '.exe' if os.name == 'nt' else ''
    def binary(name):
        found = str(args.mysql_bin / (name + executable_suffix)) if args.mysql_bin else shutil.which(name)
        if not found or not Path(found).is_file():
            raise SystemExit(f'{name} was not found. Supply --mysql-bin with your MySQL bin directory.')
        return found
    mysqld, mysql, mysqladmin = binary('mysqld'), binary('mysql'), binary('mysqladmin')
    project = Path(__file__).resolve().parents[1]
    target = project / 'target'
    target.mkdir(exist_ok=True)
    work = Path(tempfile.mkdtemp(prefix='mysql-test-', dir=target)).resolve()
    data = work / 'data'
    creationflags = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]
    environment = os.environ.copy()
    # Avoid inheriting credentials/configuration for someone else's installed service.
    environment.pop('MYSQL_PWD', None)
    with (work / 'server.log').open('wb') as log:
        init = subprocess.run([mysqld, '--no-defaults', '--initialize-insecure', f'--datadir={data}'],
                              stdout=log, stderr=log, env=environment, creationflags=creationflags)
        if init.returncode:
            raise SystemExit(f'Could not initialize disposable MySQL. Inspect {work / "server.log"}')
        process = subprocess.Popen([mysqld, '--no-defaults', f'--datadir={data}', f'--port={port}',
                                    '--bind-address=127.0.0.1', '--mysqlx=0', '--skip-log-bin',
                                    '--innodb-buffer-pool-size=32M', '--performance-schema=OFF', '--console'],
                                   stdout=log, stderr=log, env=environment, creationflags=creationflags)
        secret = None
        try:
            deadline = time.monotonic() + 50
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise SystemExit(f'Disposable MySQL stopped. Inspect {work / "server.log"}')
                try:
                    with socket.create_connection(('127.0.0.1', port), timeout=1):
                        break
                except OSError:
                    time.sleep(0.25)
            else:
                raise SystemExit('Disposable MySQL did not become ready within 50 seconds.')
            secret = secrets.token_hex(24)
            result = subprocess.run([mysql, '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                                     f'--port={port}', '--user=root'],
                                    input=f"ALTER USER 'root'@'localhost' IDENTIFIED BY '{secret}';\n",
                                    text=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
                                    env=environment, creationflags=creationflags)
            if result.returncode:
                raise SystemExit('Could not secure disposable test account. Server will be stopped.')
            environment['TCSMS_TEST_MYSQL_PASSWORD'] = secret
            maven = shutil.which('mvn')
            if not maven:
                raise SystemExit('Maven was not found on PATH.')
            print('Running contract tests on disposable MySQL; no installed service is used.', flush=True)
            command = [maven, '-Dtest=BssMySqlIT',
                       f'-Dtcsms.test.mysql.url=jdbc:mysql://127.0.0.1:{port}/?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC', 'test']
            # Start Maven's Java launcher directly on Windows so JDBC URL ampersands
            # and paths with spaces never pass through command-shell interpretation.
            if os.name == 'nt':
                maven_home = Path(maven).resolve().parents[1]
                launcher = next((maven_home / 'boot').glob('plexus-classworlds-*.jar'))
                command = [shutil.which('java'), '-classpath', str(launcher),
                           f'-Dclassworlds.conf={maven_home / "bin" / "m2.conf"}',
                           f'-Dmaven.home={maven_home}', f'-Dmaven.multiModuleProjectDirectory={project}',
                           'org.codehaus.plexus.classworlds.launcher.Launcher', '-s', str(project / 'maven-settings.xml')] + command[1:]
            result = subprocess.run(command, cwd=project, env=environment, creationflags=creationflags)
            if result.returncode:
                raise SystemExit(result.returncode)
            report = target / 'surefire-reports' / 'com.amdocs.telecom.bss.BssMySqlIT.txt'
            if not report.exists():
                raise SystemExit('Maven did not produce a MySQL test result.')
            print(report.read_text(encoding='utf-8'), flush=True)
            if args.smoke_jar:
                jar = target / 'tsatms-jar-with-dependencies.jar'
                if not jar.is_file():
                    raise SystemExit('Run mvn package before --smoke-jar.')
                schema = 'tcsms_smoke_' + secrets.token_hex(8)
                sql_environment = environment.copy()
                sql_environment['MYSQL_PWD'] = secret
                subprocess.run([mysql, '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                                f'--port={port}', '--user=root'], input=f'CREATE DATABASE {schema};\n',
                               text=True, env=sql_environment, check=True, creationflags=creationflags)
                app_environment = environment.copy()
                app_environment.update(TCSMS_DB_URL=f'jdbc:mysql://127.0.0.1:{port}/{schema}?sslMode=DISABLED&allowPublicKeyRetrieval=true&serverTimezone=UTC',
                                       TCSMS_DB_USER='root', TCSMS_DB_PASSWORD=secret,
                                       TCSMS_TIMEZONE='UTC', TCSMS_RECOVERY_URL='', TCSMS_RECOVERY_TOKEN='')
                for flags, data in [(['--init'], ''), (['--check'], ''),
                                    (['--create-admin'], 'smokeadmin\nsmoke@example.test\nSmoke!' + secrets.token_hex(16) + '\n'),
                                    ([], '5\n')]:
                    check = subprocess.run([shutil.which('java'), '-jar', str(jar), *flags], input=data,
                                           text=True, capture_output=True, env=app_environment,
                                           cwd=project, creationflags=creationflags, timeout=30)
                    if check.returncode:
                        raise SystemExit(f'Packaged app smoke check failed: {flags}. No credentials displayed.')
                print('Packaged JAR verified: additive init, database check, own admin provisioning, default console exit.', flush=True)
        finally:
            # A protocol shutdown also stops the child supervised by Windows mysqld.
            shutdown_environment = environment.copy()
            if secret is not None:
                shutdown_environment['MYSQL_PWD'] = secret
            try:
                subprocess.run([mysqladmin, '--no-defaults', '--protocol=TCP', '--host=127.0.0.1',
                                f'--port={port}', '--user=root', 'shutdown'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               env=shutdown_environment, creationflags=creationflags, timeout=15)
                process.wait(timeout=15)
            except (subprocess.TimeoutExpired, OSError):
                if os.name == 'nt' and process.poll() is None:
                    subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=creationflags)
                elif process.poll() is None:
                    process.kill()
                process.wait(timeout=5)
            print(f'Disposable MySQL stopped. Diagnostics: {work}', flush=True)


if __name__ == '__main__':
    main()
