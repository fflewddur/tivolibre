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
import shutil
import subprocess
import tempfile

class bcolors:
    OKBLUE = '\033[94m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'

class BatchTester:
    args = None
    jarPath = None
    filesTested = 0

    def run(self):
        self.args = self.parseArgs()
        with tempfile.TemporaryDirectory() as tmpDir:
            self.copyJarToTmpDir(tmpDir)
            self.printJarVersion()
            self.testFilesInDir()

    def parseArgs(self):
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
        parser.add_argument('--skip', help="Skip the first N TiVo files", metavar='N', type=int,
                            default=0)
        parser.add_argument('-j', '--jar',
                            help="Path to the TivoLibre JAR (default is " + defaultJarPath +")",
                            default=defaultJarPath, metavar="JAR_PATH")
        return parser.parse_args()

    def copyJarToTmpDir(self, tmpDir):
        destPath = os.path.join(tmpDir, 'tivo-libre.jar')
        shutil.copyfile(self.args.jar, destPath)
        self.jarPath = destPath

    def printJarVersion(self):
        result = subprocess.run(['java', '-jar', self.jarPath, '-v'], stdout=subprocess.PIPE)
        print("Running with {}".format(result.stdout.decode('utf-8')), end='')

    def testFilesInDir(self):
        tivoFiles = self.listSourceFiles()

        numSkipped = 0
        filesWithDifferences = []
        perfectFiles = []
        for filePath in tivoFiles:
            if numSkipped >= self.args.skip:
                if not self.decodeFile(filePath):
                    filesWithDifferences.append(filePath)
                else:
                    perfectFiles.append(filePath)
            else:
                numSkipped += 1

        self.printResults(perfectFiles, filesWithDifferences)

    def listSourceFiles(self):
        sourceFiles = []
        for path, subdirs, files in os.walk(self.args.testDir):
            for name in files:
                if self.isTivoFile(name):
                    sourceFiles.append(os.path.join(path, name))
        sourceFiles.sort()
        return sourceFiles

    def isTivoFile(self, name):
        return not name.startswith('.') and name.endswith(self.args.sourceExtension)

    def decodeFile(self, inputPath):
        self.filesTested += 1
        outputPath = inputPath.replace(self.args.sourceExtension, self.args.ourExtension)
        print(bcolors.OKBLUE + "Test #{:,d}: {}{}".format(self.filesTested, inputPath, bcolors.ENDC))
        if (os.path.isfile(outputPath)):
            print("Deleting existing output file...")
            os.remove(outputPath)
        print("Decoding to {}...".format(outputPath))
        subprocess.run(['java', '-jar', self.jarPath, '-m', self.args.mak, '-i', inputPath,
                        '-o', outputPath])
        referencePath = outputPath.replace(self.args.ourExtension, self.args.refExtension)
        print("Comparing output to reference file {}...".format(referencePath))
        result = subprocess.run(['diff', outputPath, referencePath])

        filesAreSame = None
        if result.returncode is not 0:
            print(bcolors.FAIL + "These files are different :(" + bcolors.ENDC)
            filesAreSame = False
        else:
            print(bcolors.OKGREEN + "These files are identical!" + bcolors.ENDC)
            filesAreSame = True

        print("Deleting output file...")
        os.remove(outputPath)
        return filesAreSame

    def printResults(self, perfectFiles, filesWithDifferences):
        if perfectFiles:
            plural = ''
            if len(perfectFiles) > 1:
                plural = 's'
            print("\nPerfectly decoded {:,d} file{}:{}".format(len(perfectFiles), plural, bcolors.OKGREEN))
            for filePath in perfectFiles:
                print("\t{}".format(filePath))
            print(bcolors.ENDC)

        if filesWithDifferences:
            plural = ''
            if len(filesWithDifferences) > 1:
                plural = 's'
            print("Need to fix {:,d} file{}:{}".format(len(filesWithDifferences), plural, bcolors.FAIL))
            for filePath in filesWithDifferences:
                print("\t{}".format(filePath))
            print(bcolors.ENDC)

if __name__ == "__main__":
    tester = BatchTester()
    tester.run()
