#!/usr/bin/python3
"""Cross-check only dummy publisher output against the pinned installed Java reader.
No real receipts/credentials, protected publication, DB, service or remote access.
"""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
from protected_local_files import digest

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location('dummy', HERE / 'test-protected-publication.py')
tests = importlib.util.module_from_spec(spec); spec.loader.exec_module(tests)
jar = Path('/opt/asep/delivery/asep-backend.jar')
assert digest(jar.read_bytes()) == 'a4fb0f1a787a10a8a86ba522c2ce1e02421e24fb4f39df138603201ed2d145b1'
source = r'''
import java.nio.file.*;
import java.lang.reflect.*;
import java.time.*;
public class PublicationCompatibility {
 public static void main(String[] a) throws Exception {
  String pkg="io.github.qhung13.asep.codedelivery.";
  Class<?> ledger=Class.forName(pkg+"JdbcDeliveryLedger");
  Field jsonField=ledger.getDeclaredField("JSON");jsonField.setAccessible(true);
  Object json=jsonField.get(null);
  Class<?> requestType=Class.forName(pkg+"TestProjectDeliveryRequest");
  Object request=json.getClass().getMethod("readValue",byte[].class,Class.class)
   .invoke(json,Files.readAllBytes(Path.of(a[0])),requestType);
  String hash=(String)requestType.getMethod("bodyHash").invoke(request);
  if(!hash.equals(a[2]))throw new AssertionError("canonical hash differs");
  Class<?> enrollment=Class.forName(pkg+"ProtectedDeliveryEnrollment");
  Class<?> records=Class.forName(pkg+"ProtectedDeliveryEnrollment$Records");
  Object reader=Proxy.newProxyInstance(records.getClassLoader(),new Class<?>[]{records},
   (o,m,v)->Files.readAllBytes(Path.of(a[1]).resolve((String)v[0])));
  Constructor<?> constructor=enrollment.getDeclaredConstructor(records,Clock.class);constructor.setAccessible(true);
  Object instance=constructor.newInstance(reader,Clock.systemUTC());
  Method require=enrollment.getDeclaredMethod("require",requestType);require.setAccessible(true);
  Object permit=require.invoke(instance,request);
  Method count=enrollment.getDeclaredMethod("inventoryCount",permit.getClass(),String.class);count.setAccessible(true);
  for(String subject:new String[]{"protected-branches","project-group-instance-runners","variable-metadata-all-levels",
    "environments-deployment-capabilities","source-push-ci-behavior"}) {
   if(!count.invoke(instance,permit,subject).equals(0))throw new AssertionError("actual dummy count differs");
  }
  System.out.println("PASS: pinned Java canonical hash, all18 reader observations and five actual dummy counts");
 }
}
'''
f = tests.Fixture()
try:
    header = dict(f.permit); header.pop('facts')
    assessment = dict(permit=header, subjects={subject: dict(status='VERIFIED', observation=o,
        review=f.notes['subjects'][subject]) for subject,o in f.observations.items()})
    f.write(f.bundle / 'assessment.json', tests.encode(assessment))
    (f.bundle / 'permit.json').unlink(); (f.bundle / 'review-notes.json').unlink()
    for path in f.evidence.iterdir():
        path.unlink()
    tests.P.run(f.context, 'stage', f.expected, True, lambda _: True)
    tests.P.run(f.context, 'import-request', f.expected, True, lambda _: True)
    tests.P.run(f.context, 'publish', f.expected, True, lambda _: True)
    with tempfile.TemporaryDirectory(prefix='asep-vnt14-publication-java-') as tmp:
        folder = Path(tmp)
        java = folder / 'PublicationCompatibility.java'; java.write_text(source)
        subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac', str(java)], check=True)
        subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java',
          '-Dloader.main=PublicationCompatibility', '-Dloader.path=' + str(folder),
          '-cp', str(jar), 'org.springframework.boot.loader.launch.PropertiesLauncher',
          str(f.review / 'request.json'), str(f.target), f.expected], cwd='/opt/asep/delivery', check=True)
finally:
    f.close()

# Invalid mode reaches the separately packaged existing harness and exits before
# any credential path/read or HTTP client request. Fixture tests cover its reads.
entry = subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java',
    '-Dloader.main=io.github.qhung13.asep.integration.internal.gitlab.VnT14ReadOnlyPreflight',
    '-Dloader.path=' + str(HERE.absolute() / 'readonly-preflight.jar'), '-cp', str(jar),
    'org.springframework.boot.loader.launch.PropertiesLauncher', '--DUMMY-invalid-mode'],
    cwd='/opt/asep/delivery', capture_output=True, text=True)
assert entry.returncode == 2 and 'Explicit Owner read-only mode' in entry.stderr, entry.stderr
print('PASS: packaged read-only preflight loads and rejects invalid mode before credential/network use')
