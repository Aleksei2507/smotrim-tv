#!/usr/bin/env python3
"""Collect raw public data for editorial review; does not publish or change the live catalog."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--raw',type=Path,required=True)
    parser.add_argument('--retry',default='',help='Comma-separated IMDb IDs to retry; existing results are kept')
    args=parser.parse_args();args.raw.mkdir(parents=True,exist_ok=True)
    build=args.raw/'.collector';build.mkdir(exist_ok=True)
    artifacts=['org/json/json/20240303/json-20240303.jar','org/jsoup/jsoup/1.18.3/jsoup-1.18.3.jar'];jars=[]
    for artifact in artifacts:
        jar=build/artifact.rsplit('/',1)[1]
        if not jar.exists():
            with urllib.request.urlopen('https://repo.maven.apache.org/maven2/'+artifact,timeout=30) as response:
                payload=response.read(4*1024*1024+1)
            if len(payload)>4*1024*1024:raise ValueError('Dependency is larger than expected')
            jar.write_bytes(payload)
        jars.append(str(jar.resolve()))
    java_home=os.environ.get('JAVA_HOME')
    binary=lambda name:str(Path(java_home)/'bin'/name) if java_home else shutil.which(name) or name
    classes=build/'classes';classes.mkdir(exist_ok=True)
    subprocess.run([binary('javac'),'-sourcepath',str(ROOT/'app/src/main/java'),'-cp',os.pathsep.join(jars),'-d',str(classes),str(ROOT/'tools/CatalogCollect.java')],check=True)
    subprocess.run([binary('java'),'-cp',os.pathsep.join([str(classes.resolve()),*jars]),'ru.smotrim.tv.CatalogCollect',str(ROOT/'catalog/curated/titles.json'),str(args.raw),args.retry],check=True)
    print('Raw results collected. Review them, then run tools/build_catalog.py --raw '+str(args.raw)+' --bundle')

if __name__=='__main__':main()
