"""SQLite transaction/schema tests using the exact SQL embedded in JournalSegments.
Does not simulate Android process lifecycle or claim physical-device validation.
"""
from pathlib import Path
import re,sqlite3,tempfile
source=(Path(__file__).parents[1]/'app/src/main/java/fr/erick/journallocal/JournalSegments.java').read_text()
sql=re.findall(r'db\.execSQL\("([^"\n]+)"',source)
def stmt(prefix):return next(s for s in sql if s.startswith(prefix))
with tempfile.TemporaryDirectory() as tmp:
 path=Path(tmp)/'journal.db'
 db=sqlite3.connect(path)
 for s in sql[:5]:db.execute(s)
 # Existing V13 checkpoint, with gaps in global event IDs.
 db.execute('UPDATE journal_segments SET first_id=11,last_id=50009,event_count=49999 WHERE segment=1')
 db.execute('UPDATE journal_segment_state SET checkpoint=50009,total=49999,active=1 WHERE id=1');db.commit()
 # Re-opening must not reset V13 state.
 for s in sql[:5]:db.execute(s)
 assert db.execute('SELECT checkpoint,total FROM journal_segment_state').fetchone()==(50009,49999)
 update=stmt('UPDATE journal_segments SET first_id=')
 checkpoint=stmt('UPDATE journal_segment_state SET checkpoint=')
 db.execute(update,(50020,50020,1,0,1));db.execute(checkpoint,(50020,1,0));db.rollback()
 assert db.execute('SELECT event_count FROM journal_segments WHERE segment=1').fetchone()[0]==49999
 assert db.execute('SELECT checkpoint FROM journal_segment_state').fetchone()[0]==50009
 db.execute(update,(50020,50020,1,0,1));db.execute(checkpoint,(50020,1,0));db.execute(stmt('UPDATE journal_segments SET sealed='),(1,));db.commit();db.close()
 db=sqlite3.connect(path)
 assert db.execute('SELECT event_count,sealed FROM journal_segments WHERE segment=1').fetchone()==(50000,1)
 db.execute(stmt('INSERT OR IGNORE INTO journal_segments(segment) VALUES(?)'),(2,))
 db.execute(stmt('UPDATE journal_segment_state SET active='),(2,))
 db.execute(update,(50030,50030,0,0,2));db.execute(checkpoint,(50030,0,0));db.commit()
 assert db.execute('SELECT checkpoint,total,active FROM journal_segment_state').fetchone()==(50030,50001,2)
 assert db.execute('SELECT first_id,last_id,event_count FROM journal_segments WHERE segment=2').fetchone()==(50030,50030,1)
 assert db.execute('SELECT event_count FROM journal_segments WHERE segment=1').fetchone()[0]==50000
 db.close()
print('PASS V13 schema reuse, rollback, checkpoint restart, 50000 boundary and non-contiguous IDs')