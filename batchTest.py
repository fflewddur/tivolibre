#!/usr/bin/env python3

#
# Copyright 2015 Todd Kulesza <todd@dropline.net>.
#
# Automate testing TivoLibre on directories of TiVo files. This program will run TivoLibre on
# each TiVo file in the specified directory and compare it to a reference file. It will then
# display a list of TiVo files that TiVoLibre decoded exactly like the reference file, and a list
# of files which did not decode to an identical MPEG as the reference file.
#
# Reference files should be in MPEG format and already be decoded using the official TiVo
# DirectShow filter. Reference files should use the same name as the TiVo file, with only a
# different extension. By default, TiVo files are expected to end with ".TiVo", reference files
# are expected to end with ".ref.mpg", and the TivoLibre output files will end in ".mpg".
#
# This file is part of TivoLibre.  TivoLibre is free software: you can redistribute it and/or
# modify it under the terms of the GNU General Public License as published by the Free Software
# Foundation, either version 3 of the License, or (at your option) any later version.
#

import argparse
import os
import subprocess

class bcolors:
    OKBLUE = '\033[94m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'

class params:
    mak = None
    testDir = None
    jarPath = './jar/tivo-libre.jar'
    jarParams = ''

def main():
    args = parseArgs()
    testFilesInDir(args)


def parseArgs():
    parser = argparse.ArgumentParser()
    defaultJarPath = os.path.join('.', 'jar', 'tivo-libre.jar')
    parser.add_argument('-m', '--mak', help="MAK for files in the test directory", required=True)
    parser.add_argument('-d', '--dir', help="Directory containing files to test", required=True,
                        metavar='TEST_DIR', dest='testDir')
    parser.add_argument('-r', help="Extension for reference files (default is .ref.mpg)",
                        default='.ref.mpg', metavar='FILE_EXTENSION', dest='refExtension')
    parser.add_argument('-t', help="Extension for TiVoLibre files (default is .mpg)",
                        default='.mpg', metavar='FILE_EXTENSION', dest='ourExtension')
    parser.add_argument('-s', help="Extension for source files (default is .TiVo)",
                        default='.TiVo', metavar='FILE_EXTENSION', dest='sourceExtension')
    parser.add_argument('-j', '--jar',
                        help="Path to the TivoLibre JAR (default is " + defaultJarPath +")",
                        default=defaultJarPath, metavar="JAR_PATH")
    return parser.parse_args()

def testFilesInDir(args):
    filesWithDifferences = []
    perfectFiles = []
    for path, subdirs, files in os.walk(args.testDir):
        for name in files:
            if not name.startswith('.') and name.endswith(args.sourceExtension):
                filePath = os.path.join(path, name)
                if not decodeFile(filePath, args):
                    filesWithDifferences.append(filePath)
                else:
                    perfectFiles.append(filePath)

    print("\nPerfectly decoded files:" + bcolors.OKGREEN)
    for filePath in perfectFiles:
        print("\t{}".format(filePath))
    print(bcolors.ENDC)

    print("Shit to fix:" + bcolors.FAIL)
    for filePath in filesWithDifferences:
        print("\t{}".format(filePath))
    print(bcolors.ENDC)

def decodeFile(inputPath, args):
    outputPath = inputPath.replace(args.sourceExtension, args.ourExtension)
    print(bcolors.OKBLUE + "Testing {}{}".format(inputPath, bcolors.ENDC))
    print("Decoding to {}...".format(outputPath))
    subprocess.run(['java', '-jar', args.jar, '-m', args.mak, '-i', inputPath,
                    '-o', outputPath])
    referencePath = outputPath.replace(args.ourExtension, args.refExtension)
    print("Comparing output to reference file {}...".format(referencePath))
    result = subprocess.run(['diff', outputPath, referencePath])
    if result.returncode is not 0:
        print(bcolors.FAIL + "These files are different :(" + bcolors.ENDC)
        return False
    else:
        print(bcolors.OKGREEN + "These files are identical!" + bcolors.ENDC)
        return True

if __name__ == "__main__":
    main()
