package OrthoMCLData::Load::Plugin::InsertOrthologousGroupsFromMcl;

@ISA = qw(GUS::PluginMgr::Plugin);

# ----------------------------------------------------------------------

use strict;
use GUS::PluginMgr::Plugin;
use FileHandle;

use GUS::Model::ApiDB::OrthologGroup;
use GUS::Model::ApiDB::OrthologGroupAaSequence;
use GUS::Model::SRes::ExternalDatabase;
use GUS::Model::SRes::ExternalDatabaseRelease;

# use ApiCommonData::Load::Util;


my $argsDeclaration =
[
    fileArg({name           => 'orthoFile',
            descr          => 'Ortholog Data (ortho.mcl). OrthologGroupName(gene and taxon count) followed by a colon then the ids for the members of the group',
            reqd           => 1,
            mustExist      => 1,
	    format         => 'OG2_1009: osa|ENS1222992 pfa|PF11_0844...',
            constraintFunc => undef,
            isList         => 0, }),

 stringArg({ descr => 'core peripheral or residual group (C, P or R)',
	     name  => 'corePeripheralResidual',
	     isList    => 0,
	     reqd  => 1,
	     constraintFunc => undef,
	   }),

 stringArg({ descr => 'Name of the External Database',
	     name  => 'extDbName',
	     isList    => 0,
	     reqd  => 1,
	     constraintFunc => undef,
	   }),


 stringArg({ descr => 'Version of the External Database Release',
	     name  => 'extDbVersion',
	     isList    => 0,
	     reqd  => 1,
	     constraintFunc => undef,
	   }),

];

my $purpose = <<PURPOSE;
Insert an ApiDB::OrthologGroup and its members from an orthomcl groups file.
PURPOSE

my $purposeBrief = <<PURPOSE_BRIEF;
Load an orthoMCL analysis result.
PURPOSE_BRIEF

my $notes = <<NOTES;
Need a script to create the mapping file.
NOTES

my $tablesAffected = <<TABLES_AFFECTED;
ApiDB.OrthologGroup,
ApiDB.OrthologGroupAASequence
TABLES_AFFECTED

my $tablesDependedOn = <<TABLES_DEPENDED_ON;
Sres.TaxonName,
Sres.ExternalDatabase,
Sres.ExternalDatabaseRelease,
Sres.ExternalAASequence
TABLES_DEPENDED_ON

my $howToRestart = <<RESTART;
The plugin can been restarted, since the same ortholog group from the same OrthoMCL analysis version will only be loaded once.
RESTART

my $failureCases = <<FAIL_CASES;

FAIL_CASES

my $documentation = { purpose          => $purpose,
                      purposeBrief     => $purposeBrief,
                      notes            => $notes,
                      tablesAffected   => $tablesAffected,
                      tablesDependedOn => $tablesDependedOn,
                      howToRestart     => $howToRestart,
                      failureCases     => $failureCases };

# ----------------------------------------------------------------------

sub new {
  my ($class) = @_;
  my $self = {};
  bless($self,$class);

  $self->initialize({ requiredDbVersion => 4,
                      cvsRevision       => '$Revision: 68598 $',
                      name              => ref($self),
                      argsDeclaration   => $argsDeclaration,
                      documentation     => $documentation});

  return $self;
}



# ======================================================================

sub run {
    my ($self) = @_;

    my $orthologFile = $self->getArg('orthoFile');

    my $corePeripheralResidual = $self->getArg('corePeripheralResidual');
    $corePeripheralResidual = uc($corePeripheralResidual);
    die "The corePeripheralResidual variable must be C or P or R. It is currently set to '$corePeripheralResidual'" if ($corePeripheralResidual !~ /^[CPR]$/);
	
    my $dbReleaseId = $self->getDbRls();

    open ORTHO_FILE, "<$orthologFile";
    my $groupCount = 0;
    my $lineCount = 0;
    while (<ORTHO_FILE>) {
        chomp;
        $lineCount++;

        if ($self->_parseGroup($_, $dbReleaseId, $corePeripheralResidual)) {
            $groupCount++;
            if (($groupCount % 1000) == 0) {
                $self->log("$groupCount ortholog groups loaded.");
            }
        } else {
            $self->log("line cannot be parsed:\n#$lineCount '$_'.");
        }
    }
    $self->log("total $lineCount lines processed, and $groupCount groups loaded.");
}

sub getDbRls {
  my ($self) = @_;

  my $name = $self->getArg('extDbName');

  my $version = $self->getArg('extDbVersion');

  my $externalDatabase = GUS::Model::SRes::ExternalDatabase->new({"name" => $name});
  $externalDatabase->retrieveFromDB();

  if (! $externalDatabase->getExternalDatabaseId()) {
    $externalDatabase->submit();
  }
  my $external_db_id = $externalDatabase->getExternalDatabaseId();

  my $externalDatabaseRel = GUS::Model::SRes::ExternalDatabaseRelease->new ({'external_database_id'=>$external_db_id,'version'=>$version});

  $externalDatabaseRel->retrieveFromDB();

  if (! $externalDatabaseRel->getExternalDatabaseReleaseId()) {
    $externalDatabaseRel->submit();
  }

  my $external_db_rel_id = $externalDatabaseRel->getExternalDatabaseReleaseId();

  return $external_db_rel_id;
}


sub _parseGroup {
    my ($self, $line, $dbReleaseId, $corePeripheralResidual) = @_;

    # example line: OG2_1009: osa|ENS1222992 pfa|PF11_0844
    if ($line = /^(\S+)\: (.*)/) {
        my $groupName = $1;
        my @genes = split(/\s+/, $2);
	my $geneCount = scalar(@genes);

        # print "group=$groupName, #genes=$geneCount, #taxon=$taxonCount\n";

        # create a OrthlogGroup instance
        my $orthoGroup = GUS::Model::ApiDB::OrthologGroup->
            new({name => $groupName,
                 number_of_members => $geneCount,
                 external_database_release_id => $dbReleaseId,
		 core_peripheral_residual => $corePeripheralResidual
                });

        for (@genes) {
            if (/(\w+)\|(\S+)/) {
		my $taxonAbbrev = $1;
		my $sourceId = $2;
		my $sequenceId = $self->getAASequenceId("$taxonAbbrev|$sourceId");
		die "Can't find an aa_sequence_id for abbrev:$taxonAbbrev source_id:$sourceId\n" if !$sequenceId;

		# create a OrthologGroupAASequence instance
		my $orthoGroupSequence = GUS::Model::ApiDB::OrthologGroupAaSequence->
		    new({aa_sequence_id => $sequenceId,
		      })->setParent($orthoGroup);
	    } else {
                $self->log("gene cannot be parsed: '$_'.");
            }
        }
        $orthoGroup->submit();
        $orthoGroup->undefPointerCache();

        return 1;
    } else {
        return 0;
    }
}

# use full form of input id "pfa|PF11_0344"
sub getAASequenceId {
  my ($self, $inputId) = @_;

  if (!$self->{idMap}) {
    my $sql = "
select aa_sequence_id, source_id, three_letter_abbrev
from apidb.OrthomclTaxon ot, dots.ExternalAaSequence s
where ot.taxon_id = s.taxon_id
";

    my $stmt = $self->prepareAndExecute($sql);
    while (my ($sequenceId, $sourceId, $taxonId) = $stmt->fetchrow_array()) {
      $self->{idMap}->{"$taxonId|$sourceId"} = $sequenceId;
    }
  }
  return $self->{idMap}->{$inputId};
}


# ----------------------------------------------------------------------

sub undoTables {
  my ($self) = @_;

  return ('ApiDB.OrthologGroupAASequence',
          'ApiDB.OrthologGroup',
	 );
}

sub undoPreprocess {
    my ($self, $dbh, $rowAlgInvocationList) = @_;
    my $rowAlgInvocations = join(',', @{$rowAlgInvocationList});

    my $cpr = "";

    my $sql ="
SELECT ap.string_value
FROM CORE.ALGORITHMPARAM ap, core.algorithmparamkey apk
WHERE ap.ALGORITHM_PARAM_KEY_ID = apk.ALGORITHM_PARAM_KEY_ID
      AND ap.ROW_ALG_INVOCATION_ID IN ($rowAlgInvocations)
      AND apk.ALGORITHM_PARAM_KEY = 'corePeripheralResidual'";

    my $sh = $dbh->prepareAndExecute($sql);
    while (my @row = $sh->fetchrow_array()) {
	die "The corePeripheralResidual value is not C, P or R" if ($row[0] !~ /^[CPR]$/);
	die "There are multiple corePeripheralResidual values for this step" if ($cpr ne "" && $cpr ne $row[0]);
	$cpr = $row[0];
    }
    $sh->finish();

    $sql = "DELETE FROM apidb.OrthologGroupAaSequence WHERE ortholog_group_id in (SELECT ortholog_group_id FROM apidb.orthologgroup WHERE core_peripheral_residual = '$cpr')";
    $sh = $dbh->prepareAndExecute($sql);
    $sh->finish();

    $sql = "DELETE FROM apidb.OrthologGroup WHERE core_peripheral_residual = '$cpr'";
    $sh = $dbh->prepareAndExecute($sql);
    $sh->finish();
}

1;
