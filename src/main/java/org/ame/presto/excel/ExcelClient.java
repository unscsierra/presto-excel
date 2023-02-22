/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ame.presto.excel;

import com.facebook.airlift.json.JsonCodec;
import com.facebook.presto.common.type.VarcharType;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.jcraft.jsch.JSchException;
import org.ame.presto.excel.protocol.SFTPSession;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static java.util.Objects.requireNonNull;

public class ExcelClient
{
    private final ExcelConfig config;
    private final String protocol;
    private final SFTPSession sftpSession;
    private static final DataFormatter DATA_FORMATTER = new DataFormatter();

    @Inject
    public ExcelClient(ExcelConfig config, JsonCodec<Map<String, List<ExcelTable>>> catalogCodec)
            throws JSchException
    {
        requireNonNull(config, "config is null");
        requireNonNull(catalogCodec, "catalogCodec is null");
        this.config = config;
        if (ProtocolType.SFTP.toString().equals(config.getProtocol())) {
            this.sftpSession = new SFTPSession(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
        }
        else {
            this.sftpSession = null;
        }
        this.protocol = config.getProtocol().toLowerCase(Locale.ENGLISH);
    }

    public Optional<ExcelTable> getTable(String schemaName, String tableName)
    {
        List<List<Object>> values = readAllValues(schemaName, tableName);
        if (values.size() == 0) {
            return Optional.empty();
        }
        ImmutableList.Builder<ExcelColumn> columns = ImmutableList.builder();
        // Assuming 1st line is always header
        List<Object> header = values.get(0);
        Set<String> columnNames = new HashSet<>();
        for (int i = 0; i < header.size(); i++) {
            String columnName = header.get(i).toString().toLowerCase(Locale.ENGLISH);
            // when empty or repeated column header, adding a placeholder column name
            if (columnName.isEmpty() || columnNames.contains(columnName)) {
                columnName = "column_" + i;
            }
            columnNames.add(columnName);
            columns.add(new ExcelColumn(columnName, VarcharType.VARCHAR));
        }
        List<List<Object>> data = values.subList(1, values.size());
        return Optional.of(new ExcelTable(tableName, columns.build(), data));
    }

    private List<List<Object>> readAllValues(String schemaName, String tableName)
    {
        try {
            InputStream inputStream = getInputStream(schemaName, tableName);
            Workbook workbook = WorkbookFactory.create(inputStream);
            // TODO: support multiple sheets
            Sheet sheet = workbook.getSheetAt(0);
            List<List<Object>> values = new ArrayList<>();
            for (Row row : sheet) {
                List<Object> value = new ArrayList<>();
                for (int i = 0; i < row.getLastCellNum(); i++) {
                    Cell cell = row.getCell(i);
                    if (cell.equals(CellType.NUMERIC)) {
                        if (DateUtil.isCellDateFormatted(cell)) {
                            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                            value.add(format.format(cell.getDateCellValue()));
                        }
                        else {
                            // avoid scientific notation
                            BigDecimal bigDecimal = new BigDecimal(cell.getNumericCellValue());
                            value.add(bigDecimal.toPlainString());
                        }
                    }
                    else {
                        value.add(DATA_FORMATTER.formatCellValue(cell));
                    }
                }
                values.add(value);
            }
            workbook.close();
            return values;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getSchemaNames()
    {
        String path = config.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            if (ProtocolType.FILE.toString().equals(protocol)) {
                return Files.list(new File(path).toPath())
                        .filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toList());
            }
            else if (sftpSession != null) {
                List<String> schemas = sftpSession.getSchemas(path);
                return ImmutableList.copyOf(schemas);
            }
            else {
                throw new RuntimeException("Unsupported protocol: " + protocol);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> getTableNames(String schemaName)
    {
        String path = config.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            if (ProtocolType.FILE.toString().equals(protocol)) {
                return Files.list(new File(path).toPath().resolve(schemaName))
                        .filter(p -> p.getFileName().toString().endsWith(".xlsx"))
                        .map(p -> p.getFileName().toString().replace(".xlsx", ""))
                        .collect(Collectors.toList());
            }
            else if (sftpSession != null) {
                List<String> tables = sftpSession.getTables(path, schemaName);
                return ImmutableList.copyOf(tables);
            }
            else {
                throw new RuntimeException("Unsupported protocol: " + protocol);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private InputStream getInputStream(String schemaName, String tableName)
    {
        String path = config.getPath();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        try {
            if (ProtocolType.FILE.toString().equals(protocol)) {
                Path filePath = new File(path).toPath().resolve(schemaName).resolve(tableName + ".xlsx");
                return filePath.toUri().toURL().openStream();
            }
            else if (sftpSession != null) {
                return sftpSession.getInputStream(path + "/" + schemaName + "/" + tableName + ".xlsx");
            }
            else {
                throw new RuntimeException("Unsupported protocol: " + protocol);
            }
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
