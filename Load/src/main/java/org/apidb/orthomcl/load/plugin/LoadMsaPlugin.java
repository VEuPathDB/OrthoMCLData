package org.apidb.orthomcl.load.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import org.apache.log4j.Logger;
import org.gusdb.fgputil.db.SqlUtils;
import org.gusdb.fgputil.db.platform.SupportedPlatform;
import org.gusdb.fgputil.db.pool.ConnectionPoolConfig;
import org.gusdb.fgputil.db.pool.DatabaseInstance;
import org.gusdb.fgputil.db.pool.SimpleDbConfig;

/**
 * @author xingao
 */
public class LoadMsaPlugin implements Plugin {

    // private static final int BASE = 70612;

    private static final Logger LOG = Logger.getLogger(LoadMsaPlugin.class);

    private ConnectionPoolConfig _dbConfig;
    private File _msaDir;

    @Override
    public void setArgs(String[] args) throws OrthoMCLException {
        // verify the args
        if (args.length != 4) {
            throw new OrthoMCLException("The args should be: <msa_dir> "
                    + " <connection_string> <login> <password>");
        }
        String msaDirName = args[1];
        String connectionString = args[2];
        String login = args[3];
        String password = args[4];

        _dbConfig = SimpleDbConfig.create(SupportedPlatform.ORACLE, connectionString, login, password);
        _msaDir = new File(msaDirName);
        if (!_msaDir.exists()) {
          throw new OrthoMCLException(new FileNotFoundException(msaDirName));
        }
    }

    @Override
    public void invoke() throws OrthoMCLException {
        LOG.info("Making connections...");
        try (DatabaseInstance db = new DatabaseInstance(_dbConfig);
             Connection connection = db.getDataSource().getConnection()){

            // get sequence map
            LOG.info("Getting sequence names...");
            //Map<String, String> sequences = getSequences(connection);

            LOG.info("Getting svg content...");

            PreparedStatement psUpdate = connection.prepareStatement("UPDATE "
                    + " apidb.OrthologGroup SET multiple_sequence_alignment = ? "
                    + " WHERE ortholog_group_id = ?");

            Statement stSelect = connection.createStatement();
            ResultSet resultSet = stSelect.executeQuery("SELECT name, "
                    + " ortholog_group_id FROM apidb.OrthologGroup "
                    + " WHERE multiple_sequence_alignment IS NULL");
            int count = 0;
            while (resultSet.next()) {
                int groupId = resultSet.getInt("ortholog_group_id");
                String name = resultSet.getString("name");

                String content = getContent(name, _msaDir);
                if (content.length() > 0) {
                    // update clob
                    SqlUtils.setClobData(psUpdate, 1, content);
                    psUpdate.setInt(2, groupId);
                    psUpdate.addBatch();

                    count++;
                }

                if (count % 100 == 0) {
                    psUpdate.executeBatch();
                    LOG.info(count + " groups updated.");
                }
            }
            if (count % 100 != 0) psUpdate.executeBatch();

            resultSet.close();
            stSelect.close();
            psUpdate.close();

            System.out.println("Total " + count + " groups updated.");
        }
        catch (Exception ex) {
            throw new OrthoMCLException(ex);
        }
    }

//    private Map<String, String> getSequences(Connection connection)
//            throws SQLException {
//        Statement stmt = connection.createStatement();
//        ResultSet rs = stmt.executeQuery("SELECT eas.aa_sequence_id, "
//                + "      eas.source_id, ot.three_letter_abbrev "
//                + " FROM dots.ExternalAaSequence eas, apidb.OrthomclTaxon ot, "
//                + "      apidb.OrthologGroupAaSequence ogs "
//                + " WHERE eas.aa_sequence_id = ogs.aa_sequence_id "
//                + "   AND eas.taxon_id = ot.taxon_id");
//        Map<String, String> sequences = new HashMap<String, String>();
//        while (rs.next()) {
//            String sequenceId = rs.getString("aa_sequence_id");
//            String sourceId = rs.getString("source_id");
//            String taxonName = rs.getString("three_letter_abbrev");
//            String name = taxonName + "|" + sourceId;
//            sequences.put(sequenceId, name.intern());
//        }
//        rs.close();
//        stmt.close();
//        return sequences;
//    }

    private static String getContent(String name, File msaDir) throws IOException {
        StringBuffer buffer = new StringBuffer();

        File msaFile = new File(msaDir, name + ".msa");
        if (!msaFile.exists()) {
            LOG.warn("MSA not found for group name: " + name);
            return buffer.toString();
        }
        String line;
        BufferedReader reader = new BufferedReader(new FileReader(msaFile));
        while ((line = reader.readLine()) != null) {
            buffer.append(line).append("\n");
        }
        reader.close();
        return buffer.toString().trim();
    }
    //
    // private String fixContent(List<String> contents,
    // Map<String, String> sequences) {
    // // get id mapping
    // int newMax = 0;
    // int oldOffset = 0;
    // for (String line : contents) {
    // if (line.matches("\\S+\\s{5,}\\S.*")) {
    // String[] parts = line.split("\\s{5,}", 2);
    //
    // String oldId = parts[0];
    // String newId = sequences.get(oldId);
    // if (newId == null) newId = oldId;
    //
    // if (newId.length() > newMax) newMax = newId.length();
    //
    // int offset = line.indexOf(parts[1], oldId.length() + 4);
    // if (offset > oldOffset) oldOffset = offset;
    // }
    // }
    // int newOffset = newMax + 10; // get the offset of new alignments
    // if (newOffset < oldOffset) newOffset = oldOffset;
    //
    // // generate output
    // StringBuffer buffer = new StringBuffer();
    // for (String line : contents) {
    // if (line.matches("\\S+\\s{5,}\\S.*")) {
    // String[] parts = line.split("\\s{5,}", 2);
    //
    // String oldId = parts[0];
    // String newId = sequences.get(oldId);
    // if (newId == null) newId = oldId;
    //
    // buffer.append(newId);
    // for (int i = newId.length(); i < newOffset; i++) {
    // buffer.append(' ');
    // }
    // buffer.append(parts[1]);
    // } else if (line.matches("\\s{6,}.*")) {
    // for (int i = oldOffset; i < newOffset; i++) {
    // buffer.append(' ');
    // }
    // buffer.append(line);
    // } else {
    // buffer.append(line);
    // }
    // buffer.append("\n");
    // }
    // return buffer.toString();
    // }
}
