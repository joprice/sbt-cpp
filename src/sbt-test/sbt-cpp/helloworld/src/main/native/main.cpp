#include <iostream>

int main(int argc, char** argv) {
  if (argc > 1) {
    std::cout << "Hello world: " << argv[1] << std::endl;
  } else {
    std::cout << "expected an argument" << std::endl;;
    return 1;
  }
 
  return 0;
}
