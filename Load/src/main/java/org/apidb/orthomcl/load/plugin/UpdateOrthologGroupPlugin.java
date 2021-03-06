package org.apidb.orthomcl.load.plugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.gusdb.fgputil.db.platform.SupportedPlatform;
import org.gusdb.fgputil.db.pool.ConnectionPoolConfig;
import org.gusdb.fgputil.db.pool.DatabaseInstance;
import org.gusdb.fgputil.db.pool.SimpleDbConfig;

/**
 * @author xingao
 */
public class UpdateOrthologGroupPlugin implements Plugin {

    private class PValue {
        double Mantissa;
        int Exponent;

        PValue() {
            Exponent = 1;
            Mantissa = 0;
        }

        PValue(double value) {
            int sign = (value >= 0) ? 1 : -1;
            Exponent = 0;
            Mantissa = Math.abs(value);
            while (Mantissa != 0 && (Mantissa < 1 || Mantissa >= 10)) {
                if (Mantissa < 1) {
                    Mantissa *= 10;
                    Exponent--;
                } else {
                    Mantissa /= 10;
                    Exponent++;
                }
            }
            Mantissa *= sign;
        }
    }

    private static final Logger LOG = Logger.getLogger(UpdateOrthologGroupPlugin.class);

    private ConnectionPoolConfig _dbConfig;
    private String _sequenceTable;

    /*
     * (non-Javadoc)
     * 
     * @see org.apidb.orthomcl.load.plugin.Plugin#invoke()
     */
    @Override
    public void invoke() throws OrthoMCLException {
        try (DatabaseInstance db = new DatabaseInstance(_dbConfig);
             Connection connection = db.getDataSource().getConnection()){
            PreparedStatement psSelectSimilarity = connection.prepareStatement("SELECT"
                    + " s.total_match_length, s.pvalue_mant, s.pvalue_exp, "
                    + " s.non_overlap_match_length, s.number_identical "
                    + " FROM dots.Similarity s "
                    + " WHERE (s.query_id = ? AND s.subject_id = ?) "
                    + " OR (s.subject_id = ? AND s.query_id = ?)");
            PreparedStatement psSelectSequence = connection.prepareStatement("SELECT"
                    + " aa_sequence_id"
                    + " FROM apidb.OrthologGroupAaSequence "
                    + " WHERE ortholog_group_id = ?");
            PreparedStatement psUpdateGroup = connection.prepareStatement("UPDATE"
                    + " apidb.OrthologGroup "
                    + " SET avg_percent_match = ?, avg_percent_identity = ?,"
                    + " avg_evalue_mant = ?, avg_evalue_exp = ?, "
                    + " avg_connectivity = ?, number_of_match_pairs = ? "
                    + " WHERE ortholog_group_id = ?");
            PreparedStatement psUpdateSequence = connection.prepareStatement("UPDATE"
                    + " apidb.OrthologGroupAaSequence SET connectivity = ?"
                    + " WHERE ortholog_group_aa_sequence_id = ?");

            LOG.info("loading sequence lengths...");
            Map<Integer, Integer> lengthMap = getSequenceLength(connection);

            // load un-finished groups
            LOG.info("checking ortholog groups to be updated...");
            Statement stSelectGroup = connection.createStatement();
            ResultSet rsGroup = stSelectGroup.executeQuery("SELECT "
                    + " ortholog_group_id, name FROM apidb.OrthologGroup "
                    + " WHERE number_of_match_pairs IS NULL");
            Map<Integer, String> groups = new HashMap<Integer, String>();
            while (rsGroup.next()) {
                int orthologGroupId = rsGroup.getInt("ortholog_group_id");
                String orthologName = rsGroup.getString("name");
                groups.put(orthologGroupId, orthologName);
            }
            rsGroup.close();
            stSelectGroup.close();

            // start updating groups
            LOG.info("updating ortholog " + groups.size() + " groups...");
            int groupCount = 0;
            for (int orthologGroupId : groups.keySet()) {
                updateOrthologGroup(orthologGroupId,
                        psSelectSimilarity, psSelectSequence, psUpdateGroup,
                        psUpdateSequence, lengthMap);
                groupCount++;
                if (groupCount % 100 == 0)
                    LOG.info(groupCount + " ortholog groups updated.");
            }
            psUpdateSequence.close();
            psUpdateGroup.close();
            psSelectSequence.close();
            psSelectSimilarity.close();

            LOG.info("Total " + groupCount + " ortholog groups updated.");
        }
        catch (Exception ex) {
            throw new OrthoMCLException(ex);
        }
    }

    @Override
    public void setArgs(String[] args) throws OrthoMCLException {
        // verify the args
        if (args.length != 4) {
            throw new OrthoMCLException("The args should be: <sequence_table> "
                    + " <connection_string> <login> <password>");
        }
        _sequenceTable = args[0];
        String connectionString = args[1];
        String login = args[2];
        String password = args[3];

        _dbConfig = SimpleDbConfig.create(SupportedPlatform.ORACLE, connectionString, login, password);
    }

    private void updateOrthologGroup(int orthologGroupId,
            PreparedStatement psSelectSimilarity,
            PreparedStatement psSelectSequence,
            PreparedStatement psUpdateGroup,
            PreparedStatement psUpdateSequence, Map<Integer, Integer> lengthMap)
            throws SQLException {
        // get the sequences in the group
        psSelectSequence.setInt(1, orthologGroupId);
        ResultSet rsSequences = psSelectSequence.executeQuery();
        List<Integer> sequences = new ArrayList<Integer>();
        while (rsSequences.next()) {
            sequences.add(rsSequences.getInt("aa_sequence_id"));
        }
        rsSequences.close();

        // for each pair of sequences, check if there are really pair
        int pairCount = 0;
        double sumPercentIdentity = 0;
        double sumPercentMatch = 0;
        double sumEvalue = 0;
        int[] connectivities = new int[sequences.size()];
        for (int i = 0; i < sequences.size() - 1; i++) {
            for (int j = i + 1; j < sequences.size(); j++) {
                int sequence1 = sequences.get(i);
                int sequence2 = sequences.get(j);
                double[] pairInfo = getPairInfo(sequence1, sequence2,
                        psSelectSimilarity, lengthMap);
                if (pairInfo != null) { // a valid pair
                    pairCount++;
                    sumPercentIdentity += pairInfo[0];
                    sumPercentMatch += pairInfo[1];
                    sumEvalue += pairInfo[2];
                    connectivities[i]++;
                    connectivities[j]++;
                }
            }
        }
        // update connectivities
        double avgConnectivity = updateSequence(sequences, connectivities,
                psUpdateSequence);

        // compute the average values for the group
        double avgPercentIdentity = sumPercentIdentity / pairCount;
        double avgPercentMatch = sumPercentMatch / pairCount;
        PValue avgEvalue;
        if (sumEvalue == 0) {
            avgEvalue = new PValue();
        } else {
            avgEvalue = new PValue(Math.pow(10, sumEvalue / pairCount));
        }
        // update ortholog groups
        psUpdateGroup.setDouble(1, avgPercentMatch);
        psUpdateGroup.setDouble(2, avgPercentIdentity);
        psUpdateGroup.setDouble(3, avgEvalue.Mantissa);
        psUpdateGroup.setDouble(4, avgEvalue.Exponent);
        psUpdateGroup.setDouble(5, avgConnectivity);
        psUpdateGroup.setInt(6, pairCount);
        psUpdateGroup.setInt(7, orthologGroupId);
        psUpdateGroup.execute();
    }

    private double[] getPairInfo(int sequenceId1, int sequenceId2,
            PreparedStatement psSelectSimilarity,
            Map<Integer, Integer> lengthMap) throws SQLException {
        psSelectSimilarity.setInt(1, sequenceId1);
        psSelectSimilarity.setInt(2, sequenceId2);
        psSelectSimilarity.setInt(3, sequenceId1);
        psSelectSimilarity.setInt(4, sequenceId2);
        ResultSet rsSimilarity = psSelectSimilarity.executeQuery();

        int seqLength = Math.max(lengthMap.get(sequenceId1),
                lengthMap.get(sequenceId2));
        double sumPercentIdentity = 0;
        double sumPercentMatch = 0;
        double sumEvalue = 0;
        int count = 0;
        while (rsSimilarity.next()) {
            double pvalueMant = rsSimilarity.getDouble("pvalue_mant");
            int pvalueExp = rsSimilarity.getInt("pvalue_exp");
            int totalMatchLength = rsSimilarity.getInt("total_match_length");
            int nonOverlapLength = rsSimilarity.getInt("non_overlap_match_length");
            int identityCount = rsSimilarity.getInt("number_identical");

            sumPercentIdentity += 100.0 * identityCount / totalMatchLength;
            sumPercentMatch += 100.0 * nonOverlapLength / seqLength;
            if (pvalueMant != 0) {
                sumEvalue += pvalueExp + Math.log10(pvalueMant);
            }

            count++;
        }
        rsSimilarity.close();

        // need to have both way matches
        if (count < 2) return null;

        return new double[] { sumPercentIdentity / count,
                sumPercentMatch / count, sumEvalue / count };
    }

    private Map<Integer, Integer> getSequenceLength(Connection connection) throws SQLException {
        Statement stSequence = connection.createStatement();
        ResultSet rsSequence = stSequence.executeQuery("SELECT aa_sequence_id, "
                + " length FROM " + _sequenceTable);
        Map<Integer, Integer> lengthMap = new HashMap<Integer, Integer>();
        while (rsSequence.next()) {
            int sequenceId = rsSequence.getInt("aa_sequence_id");
            int length = rsSequence.getInt("length");
            lengthMap.put(sequenceId, length);
        }
        rsSequence.close();
        stSequence.close();
        return lengthMap;
    }

    private double updateSequence(List<Integer> sequences,
            int[] connectivities, PreparedStatement psUpdateSequence)
            throws SQLException {
        int sumConnectivity = 0;
        int seqIndex;
        for (seqIndex = 0; seqIndex < connectivities.length; seqIndex++) {
            psUpdateSequence.setInt(1, connectivities[seqIndex]);
            psUpdateSequence.setInt(2, sequences.get(seqIndex));
            psUpdateSequence.addBatch();
            if (seqIndex % 1000 == 0) psUpdateSequence.executeBatch();
            sumConnectivity += connectivities[seqIndex];
        }
        if (seqIndex % 1000 != 0) psUpdateSequence.executeBatch();

        return (double) sumConnectivity / connectivities.length;
    }
}
