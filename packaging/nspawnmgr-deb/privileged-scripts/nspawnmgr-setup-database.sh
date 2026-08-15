#!/bin/sh
# Runs as root (via the NOPASSWD sudoers grant) to create/inspect/drop the opinionated "nspawnmgr"
# and "guacamole" databases during first-boot setup (DatabaseSetupWizardServlet's "localhost"
# path) — when there's no root/superuser DB credential to connect with directly, the database
# changes really do just happen via "sudo mysql"/"sudo -u postgres psql", exactly as a human admin
# would do it by hand. Every sub-command takes only fixed-shape arguments (vendor, and
# database/user names that are always this project's own opinionated constants, never
# admin-typed) — the one place free-form content flows in is a password, always via stdin, never
# a command-line argument (so it never appears in `ps`/shell history).
#
# Deliberately omits any "-h"/"--host" flag for mysql: passing none (or literally "localhost", as
# opposed to "127.0.0.1") makes the client use the local Unix socket, which is what lets root
# connect passwordlessly via the auth_socket/unix_socket plugin - the "sudo mysql" behavior this
# whole path exists to replicate. Forcing a TCP connection here would need a password we don't have.
#
# Usage:
#   nspawnmgr-setup-database.sh check-db         <mysql|postgresql> <database>            -> prints 1 or 0
#   nspawnmgr-setup-database.sh check-table      <mysql|postgresql> <database> <table>    -> prints 1 or 0
#   nspawnmgr-setup-database.sh check-user       <mysql|postgresql> <username>            -> prints 1 or 0
#   nspawnmgr-setup-database.sh check-has-tables <mysql|postgresql> <database>            -> prints 1 or 0
#   nspawnmgr-setup-database.sh create           <mysql|postgresql> <database> <username> -> password via stdin
#   nspawnmgr-setup-database.sh drop             <mysql|postgresql> <database> <username> -> no prompt (caller
#                                                                                             already confirmed)
set -e

subcommand="$1"
vendor="$2"
shift 2

case "$vendor" in
    mysql | postgresql) ;;
    *) echo "Unknown vendor: $vendor" >&2; exit 1 ;;
esac

case "$subcommand" in
    check-db)
        database="$1"
        if [ "$vendor" = mysql ]; then
            mysql -N -B -e "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME='$database'"
        else
            su postgres -c "psql -tAc \"SELECT COUNT(*) FROM pg_database WHERE datname='$database'\""
        fi
        ;;
    check-table)
        database="$1"
        table="$2"
        if [ "$vendor" = mysql ]; then
            mysql -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$database' AND TABLE_NAME='$table'"
        else
            su postgres -c "psql -d '$database' -tAc \"SELECT COUNT(*) FROM information_schema.tables WHERE table_name='$table'\"" \
                || echo 0
        fi
        ;;
    check-user)
        username="$1"
        if [ "$vendor" = mysql ]; then
            mysql -N -B -e "SELECT COUNT(*) FROM mysql.user WHERE User='$username' AND Host='localhost'"
        else
            su postgres -c "psql -tAc \"SELECT COUNT(*) FROM pg_roles WHERE rolname='$username'\""
        fi
        ;;
    check-has-tables)
        database="$1"
        if [ "$vendor" = mysql ]; then
            mysql -N -B -e "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='$database'" \
                | awk '{print ($1 > 0) ? 1 : 0}'
        else
            su postgres -c "psql -d '$database' -tAc \"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'\"" \
                | awk '{print ($1 > 0) ? 1 : 0}'
        fi
        ;;
    create)
        database="$1"
        username="$2"
        password="$(cat)"
        if [ "$vendor" = mysql ]; then
            mysql <<SQL
CREATE DATABASE IF NOT EXISTS \`$database\` CHARACTER SET utf8mb4;
CREATE USER IF NOT EXISTS '$username'@'localhost' IDENTIFIED BY '$password';
GRANT ALL PRIVILEGES ON \`$database\`.* TO '$username'@'localhost';
FLUSH PRIVILEGES;
SQL
        else
            su postgres -c 'psql -v ON_ERROR_STOP=1' <<SQL
DO \$body\$
BEGIN
   IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '$username') THEN
      CREATE ROLE "$username" LOGIN PASSWORD '$password';
   END IF;
END
\$body\$;
CREATE DATABASE "$database" OWNER "$username";
GRANT ALL PRIVILEGES ON DATABASE "$database" TO "$username";
SQL
        fi
        ;;
    drop)
        database="$1"
        username="$2"
        if [ "$vendor" = mysql ]; then
            mysql -e "DROP DATABASE IF EXISTS \`$database\`; DROP USER IF EXISTS '$username'@'localhost';"
        else
            su postgres -c 'psql -v ON_ERROR_STOP=1' <<SQL
DROP DATABASE IF EXISTS "$database";
DROP ROLE IF EXISTS "$username";
SQL
        fi
        ;;
    *)
        echo "Unknown sub-command: $subcommand" >&2
        exit 1
        ;;
esac
